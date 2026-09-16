/*
 * Copyright 2026 IntelliStream AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * window.SecretCrypto — the only file that encrypts, decrypts or draws random bytes for one-time
 * secrets. /secrets seals with it and /s/{id} opens with it, so there is one implementation of the
 * format and no second copy that disagrees about it (SecretClientGuardTest fails the build on a
 * crypto.subtle or getRandomValues call anywhere else, and on Math.random here).
 *
 * The format, which SecretCodec on the server checks the shape of:
 *
 *   key       32 random bytes. Travels only in the link's #fragment, which browsers never send to
 *             a server, or — "send the key separately" — outside the app entirely.
 *   encKey    HKDF-SHA-256(key, info "ichat-secret-v1 enc")    → AES-256-GCM key
 *   verifier  HKDF-SHA-256(key, info "ichat-secret-v1 verify") → 32 bytes, sent to the server,
 *             which keeps only its SHA-256 and asks for it before answering anything about the
 *             secret. Two HKDF labels, so the value the server sees and the key that decrypts are
 *             independent: knowing the verifier gets you no closer to the plaintext.
 *   payload   0x01 ‖ IV(12, random) ‖ AES-GCM(plaintext, AAD = 0x01) ‖ tag(16), base64url.
 *             The version byte is also the additional authenticated data, so it cannot be changed
 *             without the tag failing.
 *
 * Web Crypto exists only in a secure context (HTTPS, or localhost). available() says so, and both
 * pages explain it rather than failing on an undefined crypto.subtle.
 *
 * Promise chains rather than async/await, here and on both pages. Web Crypto and fetch only hand
 * back promises so the asynchrony is unavoidable, but every await is a place where the function
 * stops and the browser gets a rendering opportunity — so DOM work split across awaits is style and
 * layout recalculated once per piece, where one synchronous block would have cost one pass. Written
 * as chains, each .then() is visibly one such piece, and the callers keep their DOM updates inside a
 * single one.
 */
window.SecretCrypto = (function () {
  'use strict';

  const VERSION = 0x01;
  const KEY_BYTES = 32;
  const IV_BYTES = 12;
  const INFO_ENC = 'ichat-secret-v1 enc';
  const INFO_VERIFY = 'ichat-secret-v1 verify';
  const KEY_PATTERN = /^[A-Za-z0-9_-]{43}$/;

  const utf8 = new TextEncoder();

  const available = () => !!(window.isSecureContext && window.crypto && window.crypto.subtle
      && typeof window.crypto.getRandomValues === 'function');

  const toBase64Url = (bytes) => {
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  };

  const fromBase64Url = (text) => {
    if (typeof text !== 'string' || !/^[A-Za-z0-9_-]*$/.test(text)) throw new Error('not-base64url');
    let s = text.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    const binary = atob(s);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  };

  const hkdfParams = (info) => ({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: utf8.encode(info) });

  const importBase = (keyBytes) =>
    window.crypto.subtle.importKey('raw', keyBytes, 'HKDF', false, ['deriveBits', 'deriveKey']);

  const deriveAesKey = (keyBytes, usage) =>
    importBase(keyBytes).then((base) => window.crypto.subtle.deriveKey(
        hkdfParams(INFO_ENC), base, { name: 'AES-GCM', length: 256 }, false, [usage]));

  const deriveVerifier = (keyBytes) =>
    importBase(keyBytes)
        .then((base) => window.crypto.subtle.deriveBits(hkdfParams(INFO_VERIFY), base, 256))
        .then((bits) => new Uint8Array(bits));

  /**
   * The 32-byte key from what a person pasted or the fragment carried — tolerating a leading '#'
   * and surrounding whitespace, nothing more. Null when it is not a key this app made.
   */
  const parseKey = (text) => {
    if (typeof text !== 'string') return null;
    const trimmed = text.trim().replace(/^#/, '');
    if (!KEY_PATTERN.test(trimmed)) return null;
    const bytes = fromBase64Url(trimmed);
    return bytes.length === KEY_BYTES ? bytes : null;
  };

  /** UTF-8 size of a secret, which is what the server's cap counts. */
  const byteLength = (text) => utf8.encode(text).length;

  /**
   * Encrypts a secret under a fresh key.
   * @returns {Promise<{payload: string, verifier: string, key: string}>} all base64url
   */
  const seal = (plaintext) => {
    const key = window.crypto.getRandomValues(new Uint8Array(KEY_BYTES));
    const iv = window.crypto.getRandomValues(new Uint8Array(IV_BYTES));
    return deriveAesKey(key, 'encrypt')
        .then((aes) => window.crypto.subtle.encrypt(
            { name: 'AES-GCM', iv: iv, additionalData: new Uint8Array([VERSION]), tagLength: 128 },
            aes, utf8.encode(plaintext)))
        .then((ciphertext) => {
          const sealed = new Uint8Array(ciphertext);
          const payload = new Uint8Array(1 + IV_BYTES + sealed.length);
          payload[0] = VERSION;
          payload.set(iv, 1);
          payload.set(sealed, 1 + IV_BYTES);
          return deriveVerifier(key).then((verifier) => ({
            payload: toBase64Url(payload),
            verifier: toBase64Url(verifier),
            key: toBase64Url(key),
          }));
        });
  };

  /** The verifier for a key, base64url; null when the text is not a key. */
  const verifierFor = (keyText) => {
    const key = parseKey(keyText);
    return key ? deriveVerifier(key).then(toBase64Url) : Promise.resolve(null);
  };

  /**
   * Decrypts a payload. Rejects with Error('wrong-key') when authentication fails — a wrong key
   * and a tampered payload look identical to AES-GCM, and neither ever yields partial text.
   */
  const openPayload = (payloadText, keyText) => {
    let key;
    let payload;
    try {
      key = parseKey(keyText);
      payload = fromBase64Url(payloadText);
    } catch (e) {
      return Promise.reject(new Error('wrong-key'));
    }
    if (!key) return Promise.reject(new Error('wrong-key'));
    if (payload.length <= 1 + IV_BYTES + 16 || payload[0] !== VERSION) {
      return Promise.reject(new Error('unknown-format'));
    }
    return deriveAesKey(key, 'decrypt')
        .then((aes) => window.crypto.subtle.decrypt(
            { name: 'AES-GCM', iv: payload.subarray(1, 1 + IV_BYTES), additionalData: new Uint8Array([VERSION]), tagLength: 128 },
            aes, payload.subarray(1 + IV_BYTES)))
        .then(
            (plain) => new TextDecoder('utf-8', { fatal: true }).decode(plain),
            () => { throw new Error('wrong-key'); });
  };

  return {
    available: available,
    parseKey: parseKey,
    byteLength: byteLength,
    seal: seal,
    verifierFor: verifierFor,
    openPayload: openPayload,
  };
})();
