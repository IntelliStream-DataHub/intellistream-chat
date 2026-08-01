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

package ai.intellistream.chat.calls;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Config for 1:1 audio and video calls.
 *
 * <p><b>Calls are off until an operator configures TURN</b>, and that is deliberate rather than
 * cautious. {@link #isForceRelay()} defaults to true, which means every call is relayed and a
 * deployment with no TURN server has no media path at all — so shipping an enabled-by-default call
 * button would put a control in the header that cannot work. {@link #isConfigured()} is what the
 * page and the ICE endpoint both consult, and it answers false until there is somewhere for the
 * media to go.
 *
 * <p><b>Why relay everything.</b> Peer-to-peer is the cheaper path and this refuses it by default
 * for two reasons. The first is that direct connections fail for a minority of users — symmetric
 * NAT, corporate firewalls — and they fail late, after the ringing UI has promised a call, which is
 * the worst moment to discover a network topology. Relaying everything makes the behaviour uniform:
 * it works for everybody or it works for nobody, and the second is a configuration error you find
 * once rather than a support ticket you get forever. The second reason is that in a direct call each
 * participant learns the other's IP address, and this is a workspace tool for people who chose to
 * self-host.
 *
 * <p>Set {@code force-relay=false} to allow direct connections where they work and fall back to the
 * relay where they don't — the standard WebRTC trade, cheaper on bandwidth, and worth taking if the
 * relay's egress is the constraint.
 *
 * <p><b>Single-instance only.</b> {@link CallRegistry} holds ring state in memory, exactly as
 * {@code RateLimiter} holds its windows, so a second node would not know about a call ringing on the
 * first. That is fine while the deployment is one process and is listed with the rest of horizontal
 * scaling.
 */
@Component
@ConfigurationProperties("ichat.calls")
public class CallProperties {

    /** Master switch. Even when true, {@link #isConfigured()} still requires a TURN server. */
    private boolean enabled = true;

    /**
     * TURN URLs handed to the browser, e.g. {@code turn:chat.example.com:3478?transport=udp} and
     * {@code turns:chat.example.com:5349?transport=tcp}.
     *
     * <p>Configure both, and put the TLS one on 443 if you can give coturn its own address or an
     * SNI route. UDP/3478 is the fast path; TURNS on 443 is the one that survives a corporate
     * firewall, because on the wire it is indistinguishable from HTTPS. A deployment with only the
     * UDP entry works in the office it was tested in and fails at a customer site.
     */
    private List<String> turnUrls = List.of();

    /**
     * Shared secret matching coturn's {@code static-auth-secret}, used to mint the short-lived
     * credentials in {@link TurnCredentialService}. There is no default: a TURN server with a
     * guessable secret is an open relay, and the bandwidth it gives away is yours.
     */
    private String turnSecret = "";

    /**
     * STUN URLs. Unused while {@link #isForceRelay()} is true — a relay-only client never gathers
     * server-reflexive candidates — and present so that turning force-relay off does not also
     * require discovering that STUN needed configuring.
     */
    private List<String> stunUrls = List.of();

    /**
     * How long a minted TURN credential stays valid. Short on purpose: it only has to survive ICE
     * gathering at the start of one call, and a leaked credential is a stranger relaying traffic
     * through your server until it expires. Long enough that a slow ring does not outlive it.
     */
    private Duration credentialTtl = Duration.ofMinutes(10);

    /** Relay every call rather than allowing direct peer-to-peer. See the class note. */
    private boolean forceRelay = true;

    /** Offer video calls as well as audio. Audio-only deployments can drop the camera button. */
    private boolean video = true;

    /**
     * How long an unanswered call rings before the caller is told nobody picked up. Also the
     * backstop that retires a call whose caller vanished mid-ring without sending a hangup.
     */
    private Duration ringTimeout = Duration.ofSeconds(45);

    /**
     * True when calls can actually be placed. Requires the feature on, a TURN server to relay
     * through, and a secret to authenticate to it with.
     *
     * <p>The TURN requirement is unconditional rather than tied to {@link #isForceRelay()}. Without
     * force-relay a call between two well-connected peers would connect with no TURN at all, so in
     * principle this could return true — but it would mean shipping a feature that works for the
     * operator testing it on one LAN and fails for the first user behind a hostile NAT. TURN is what
     * makes calling something you can promise rather than something that usually works.
     */
    public boolean isConfigured() {
        return enabled && !turnUrls.isEmpty() && !turnSecret.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getTurnUrls() { return turnUrls; }
    public void setTurnUrls(List<String> turnUrls) { this.turnUrls = turnUrls; }

    public String getTurnSecret() { return turnSecret; }
    public void setTurnSecret(String turnSecret) { this.turnSecret = turnSecret; }

    public List<String> getStunUrls() { return stunUrls; }
    public void setStunUrls(List<String> stunUrls) { this.stunUrls = stunUrls; }

    public Duration getCredentialTtl() { return credentialTtl; }
    public void setCredentialTtl(Duration credentialTtl) { this.credentialTtl = credentialTtl; }

    public boolean isForceRelay() { return forceRelay; }
    public void setForceRelay(boolean forceRelay) { this.forceRelay = forceRelay; }

    public boolean isVideo() { return video; }
    public void setVideo(boolean video) { this.video = video; }

    public Duration getRingTimeout() { return ringTimeout; }
    public void setRingTimeout(Duration ringTimeout) { this.ringTimeout = ringTimeout; }
}
