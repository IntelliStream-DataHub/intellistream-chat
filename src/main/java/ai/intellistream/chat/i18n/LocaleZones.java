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

package ai.intellistream.chat.i18n;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Guesses an IANA time zone from the locale a browser advertised in {@code Accept-Language}.
 *
 * <p>This is the <em>pre-JavaScript</em> answer, and only that. The browser knows its real zone
 * and {@code time-format.js} posts it back on first load, after which the stored detection is used
 * and this class is not consulted again for that user. What it buys is the first paint of the first
 * page: without it, a Norwegian signing in for the first time reads a screen of UTC timestamps
 * until a round trip completes. With it they read Oslo time, and the correction (if any) is
 * invisible.
 *
 * <p>Two rules, and both are deliberately conservative, because a confident wrong guess is worse
 * than an admitted absent one — an absent one shows the user a banner asking them to pick.
 *
 * <ol>
 *   <li><b>Region wins.</b> {@code nb-NO} is Norway, full stop. A locale with a region is answered
 *       from the region alone; the language is not consulted.</li>
 *   <li><b>A bare language is resolved through CLDR's likely-subtags rule</b> — the same
 *       "{@code nb} means {@code nb-Latn-NO}" table browsers use — via {@link #LIKELY_REGION}.
 *       Where CLDR's answer is a country spanning several zones (English implies the United
 *       States, Spanish implies Spain) the guess is dropped rather than narrowed by coin flip.</li>
 * </ol>
 *
 * <p>{@link #COUNTRY_ZONES} therefore holds only countries where the whole country keeps one clock.
 * A country with more than one is simply absent, which is how "impossible to guess" is expressed.
 * A handful of countries are listed with a single zone even though tzdb knows a second, where the
 * second covers a population small enough that offering no answer would be the worse trade —
 * China (Asia/Shanghai is the country's legal time), Malaysia and Uzbekistan (both of whose pairs
 * have kept identical offsets for decades), New Zealand (Pacific/Auckland; the Chathams are some
 * six hundred people). Anyone this is wrong for is corrected by their own browser a moment later,
 * and can pin the answer on their profile page.
 *
 * <p>Not derived from tzdb at runtime because the JDK exposes no country-to-zone mapping: {@code
 * ZoneId.getAvailableZoneIds()} is a flat list of names. The table below is the mapping, kept as
 * data.
 */
public final class LocaleZones {

    private LocaleZones() {
    }

    /**
     * The zone a country keeps, for countries that keep exactly one. Absence means either "several
     * zones" (US, Canada, Brazil, Russia, Australia, Mexico, Indonesia, Kazakhstan, Chile, Ecuador,
     * Spain, Portugal, Greenland, DR Congo, Mongolia, French Polynesia, Kiribati, Micronesia) or
     * "not a country tzdb tracks".
     */
    private static final Map<String, String> COUNTRY_ZONES = Map.ofEntries(
            // --- Europe -------------------------------------------------------------------
            Map.entry("AD", "Europe/Andorra"),
            Map.entry("AL", "Europe/Tirane"),
            Map.entry("AT", "Europe/Vienna"),
            Map.entry("AX", "Europe/Helsinki"),
            Map.entry("BA", "Europe/Sarajevo"),
            Map.entry("BE", "Europe/Brussels"),
            Map.entry("BG", "Europe/Sofia"),
            Map.entry("BY", "Europe/Minsk"),
            Map.entry("CH", "Europe/Zurich"),
            Map.entry("CY", "Asia/Nicosia"),
            Map.entry("CZ", "Europe/Prague"),
            Map.entry("DE", "Europe/Berlin"),
            Map.entry("DK", "Europe/Copenhagen"),
            Map.entry("EE", "Europe/Tallinn"),
            Map.entry("FI", "Europe/Helsinki"),
            Map.entry("FO", "Atlantic/Faroe"),
            Map.entry("FR", "Europe/Paris"),
            Map.entry("GB", "Europe/London"),
            Map.entry("GG", "Europe/Guernsey"),
            Map.entry("GI", "Europe/Gibraltar"),
            Map.entry("GR", "Europe/Athens"),
            Map.entry("HR", "Europe/Zagreb"),
            Map.entry("HU", "Europe/Budapest"),
            Map.entry("IE", "Europe/Dublin"),
            Map.entry("IM", "Europe/Isle_of_Man"),
            Map.entry("IS", "Atlantic/Reykjavik"),
            Map.entry("IT", "Europe/Rome"),
            Map.entry("JE", "Europe/Jersey"),
            Map.entry("LI", "Europe/Vaduz"),
            Map.entry("LT", "Europe/Vilnius"),
            Map.entry("LU", "Europe/Luxembourg"),
            Map.entry("LV", "Europe/Riga"),
            Map.entry("MC", "Europe/Monaco"),
            Map.entry("MD", "Europe/Chisinau"),
            Map.entry("ME", "Europe/Podgorica"),
            Map.entry("MK", "Europe/Skopje"),
            Map.entry("MT", "Europe/Malta"),
            Map.entry("NL", "Europe/Amsterdam"),
            Map.entry("NO", "Europe/Oslo"),
            Map.entry("PL", "Europe/Warsaw"),
            Map.entry("RO", "Europe/Bucharest"),
            Map.entry("RS", "Europe/Belgrade"),
            Map.entry("SE", "Europe/Stockholm"),
            Map.entry("SI", "Europe/Ljubljana"),
            Map.entry("SJ", "Europe/Oslo"),
            Map.entry("SK", "Europe/Bratislava"),
            Map.entry("SM", "Europe/San_Marino"),
            Map.entry("TR", "Europe/Istanbul"),
            Map.entry("UA", "Europe/Kyiv"),
            Map.entry("VA", "Europe/Vatican"),

            // --- Americas (single-zone only; US/CA/BR/MX/CL/EC are not here) ---------------
            Map.entry("AG", "America/Antigua"),
            Map.entry("AI", "America/Anguilla"),
            Map.entry("AR", "America/Argentina/Buenos_Aires"),
            Map.entry("AW", "America/Aruba"),
            Map.entry("BB", "America/Barbados"),
            Map.entry("BL", "America/St_Barthelemy"),
            Map.entry("BM", "Atlantic/Bermuda"),
            Map.entry("BO", "America/La_Paz"),
            Map.entry("BQ", "America/Kralendijk"),
            Map.entry("BS", "America/Nassau"),
            Map.entry("BZ", "America/Belize"),
            Map.entry("CO", "America/Bogota"),
            Map.entry("CR", "America/Costa_Rica"),
            Map.entry("CU", "America/Havana"),
            Map.entry("CW", "America/Curacao"),
            Map.entry("DM", "America/Dominica"),
            Map.entry("DO", "America/Santo_Domingo"),
            Map.entry("FK", "Atlantic/Stanley"),
            Map.entry("GD", "America/Grenada"),
            Map.entry("GF", "America/Cayenne"),
            Map.entry("GP", "America/Guadeloupe"),
            Map.entry("GT", "America/Guatemala"),
            Map.entry("GY", "America/Guyana"),
            Map.entry("HN", "America/Tegucigalpa"),
            Map.entry("HT", "America/Port-au-Prince"),
            Map.entry("JM", "America/Jamaica"),
            Map.entry("KN", "America/St_Kitts"),
            Map.entry("KY", "America/Cayman"),
            Map.entry("LC", "America/St_Lucia"),
            Map.entry("MF", "America/Marigot"),
            Map.entry("MQ", "America/Martinique"),
            Map.entry("MS", "America/Montserrat"),
            Map.entry("NI", "America/Managua"),
            Map.entry("PA", "America/Panama"),
            Map.entry("PE", "America/Lima"),
            Map.entry("PM", "America/Miquelon"),
            Map.entry("PR", "America/Puerto_Rico"),
            Map.entry("PY", "America/Asuncion"),
            Map.entry("SR", "America/Paramaribo"),
            Map.entry("SV", "America/El_Salvador"),
            Map.entry("SX", "America/Lower_Princes"),
            Map.entry("TC", "America/Grand_Turk"),
            Map.entry("TT", "America/Port_of_Spain"),
            Map.entry("UY", "America/Montevideo"),
            Map.entry("VC", "America/St_Vincent"),
            Map.entry("VE", "America/Caracas"),
            Map.entry("VG", "America/Tortola"),
            Map.entry("VI", "America/St_Thomas"),

            // --- Africa (all single-zone except CD) ---------------------------------------
            Map.entry("AO", "Africa/Luanda"),
            Map.entry("BF", "Africa/Ouagadougou"),
            Map.entry("BI", "Africa/Bujumbura"),
            Map.entry("BJ", "Africa/Porto-Novo"),
            Map.entry("BW", "Africa/Gaborone"),
            Map.entry("CF", "Africa/Bangui"),
            Map.entry("CG", "Africa/Brazzaville"),
            Map.entry("CI", "Africa/Abidjan"),
            Map.entry("CM", "Africa/Douala"),
            Map.entry("CV", "Atlantic/Cape_Verde"),
            Map.entry("DJ", "Africa/Djibouti"),
            Map.entry("DZ", "Africa/Algiers"),
            Map.entry("EG", "Africa/Cairo"),
            Map.entry("EH", "Africa/El_Aaiun"),
            Map.entry("ER", "Africa/Asmara"),
            Map.entry("ET", "Africa/Addis_Ababa"),
            Map.entry("GA", "Africa/Libreville"),
            Map.entry("GH", "Africa/Accra"),
            Map.entry("GM", "Africa/Banjul"),
            Map.entry("GN", "Africa/Conakry"),
            Map.entry("GQ", "Africa/Malabo"),
            Map.entry("GW", "Africa/Bissau"),
            Map.entry("KE", "Africa/Nairobi"),
            Map.entry("KM", "Indian/Comoro"),
            Map.entry("LR", "Africa/Monrovia"),
            Map.entry("LS", "Africa/Maseru"),
            Map.entry("LY", "Africa/Tripoli"),
            Map.entry("MA", "Africa/Casablanca"),
            Map.entry("MG", "Indian/Antananarivo"),
            Map.entry("ML", "Africa/Bamako"),
            Map.entry("MR", "Africa/Nouakchott"),
            Map.entry("MU", "Indian/Mauritius"),
            Map.entry("MW", "Africa/Blantyre"),
            Map.entry("MZ", "Africa/Maputo"),
            Map.entry("NA", "Africa/Windhoek"),
            Map.entry("NE", "Africa/Niamey"),
            Map.entry("NG", "Africa/Lagos"),
            Map.entry("RE", "Indian/Reunion"),
            Map.entry("RW", "Africa/Kigali"),
            Map.entry("SC", "Indian/Mahe"),
            Map.entry("SD", "Africa/Khartoum"),
            Map.entry("SH", "Atlantic/St_Helena"),
            Map.entry("SL", "Africa/Freetown"),
            Map.entry("SN", "Africa/Dakar"),
            Map.entry("SO", "Africa/Mogadishu"),
            Map.entry("SS", "Africa/Juba"),
            Map.entry("ST", "Africa/Sao_Tome"),
            Map.entry("SZ", "Africa/Mbabane"),
            Map.entry("TD", "Africa/Ndjamena"),
            Map.entry("TG", "Africa/Lome"),
            Map.entry("TN", "Africa/Tunis"),
            Map.entry("TZ", "Africa/Dar_es_Salaam"),
            Map.entry("UG", "Africa/Kampala"),
            Map.entry("ZA", "Africa/Johannesburg"),
            Map.entry("ZM", "Africa/Lusaka"),
            Map.entry("ZW", "Africa/Harare"),

            // --- Asia (ID / KZ / MN / RU are multi-zone and absent) ------------------------
            Map.entry("AE", "Asia/Dubai"),
            Map.entry("AF", "Asia/Kabul"),
            Map.entry("AM", "Asia/Yerevan"),
            Map.entry("AZ", "Asia/Baku"),
            Map.entry("BD", "Asia/Dhaka"),
            Map.entry("BH", "Asia/Bahrain"),
            Map.entry("BN", "Asia/Brunei"),
            Map.entry("BT", "Asia/Thimphu"),
            Map.entry("CN", "Asia/Shanghai"),
            Map.entry("GE", "Asia/Tbilisi"),
            Map.entry("HK", "Asia/Hong_Kong"),
            Map.entry("IL", "Asia/Jerusalem"),
            Map.entry("IN", "Asia/Kolkata"),
            Map.entry("IQ", "Asia/Baghdad"),
            Map.entry("IR", "Asia/Tehran"),
            Map.entry("JO", "Asia/Amman"),
            Map.entry("JP", "Asia/Tokyo"),
            Map.entry("KG", "Asia/Bishkek"),
            Map.entry("KH", "Asia/Phnom_Penh"),
            Map.entry("KP", "Asia/Pyongyang"),
            Map.entry("KR", "Asia/Seoul"),
            Map.entry("KW", "Asia/Kuwait"),
            Map.entry("LA", "Asia/Vientiane"),
            Map.entry("LB", "Asia/Beirut"),
            Map.entry("LK", "Asia/Colombo"),
            Map.entry("MM", "Asia/Yangon"),
            Map.entry("MO", "Asia/Macau"),
            Map.entry("MV", "Indian/Maldives"),
            Map.entry("MY", "Asia/Kuala_Lumpur"),
            Map.entry("NP", "Asia/Kathmandu"),
            Map.entry("OM", "Asia/Muscat"),
            Map.entry("PH", "Asia/Manila"),
            Map.entry("PK", "Asia/Karachi"),
            Map.entry("PS", "Asia/Gaza"),
            Map.entry("QA", "Asia/Qatar"),
            Map.entry("SA", "Asia/Riyadh"),
            Map.entry("SG", "Asia/Singapore"),
            Map.entry("SY", "Asia/Damascus"),
            Map.entry("TH", "Asia/Bangkok"),
            Map.entry("TJ", "Asia/Dushanbe"),
            Map.entry("TL", "Asia/Dili"),
            Map.entry("TM", "Asia/Ashgabat"),
            Map.entry("TW", "Asia/Taipei"),
            Map.entry("UZ", "Asia/Tashkent"),
            Map.entry("VN", "Asia/Ho_Chi_Minh"),
            Map.entry("YE", "Asia/Aden"),

            // --- Oceania (AU / PF / KI / FM are multi-zone and absent) ---------------------
            Map.entry("AS", "Pacific/Pago_Pago"),
            Map.entry("CK", "Pacific/Rarotonga"),
            Map.entry("FJ", "Pacific/Fiji"),
            Map.entry("GU", "Pacific/Guam"),
            Map.entry("MH", "Pacific/Majuro"),
            Map.entry("MP", "Pacific/Saipan"),
            Map.entry("NC", "Pacific/Noumea"),
            Map.entry("NF", "Pacific/Norfolk"),
            Map.entry("NR", "Pacific/Nauru"),
            Map.entry("NU", "Pacific/Niue"),
            Map.entry("NZ", "Pacific/Auckland"),
            Map.entry("PG", "Pacific/Port_Moresby"),
            Map.entry("PW", "Pacific/Palau"),
            Map.entry("SB", "Pacific/Guadalcanal"),
            Map.entry("TK", "Pacific/Fakaofo"),
            Map.entry("TO", "Pacific/Tongatapu"),
            Map.entry("TV", "Pacific/Funafuti"),
            Map.entry("VU", "Pacific/Efate"),
            Map.entry("WF", "Pacific/Wallis"),
            Map.entry("WS", "Pacific/Apia")
    );

    /**
     * CLDR's likely region for a bare language subtag — the {@code nb} to {@code NO} step.
     *
     * <p>Only languages whose likely region is in {@link #COUNTRY_ZONES} earn an entry; the rest
     * would resolve to nothing anyway. That is why {@code en}, {@code es}, {@code pt} and
     * {@code ru} are missing: CLDR's answers for them (US, ES, PT, RU) are all multi-zone
     * countries, so a browser sending a bare {@code en} tells us nothing about the clock and is
     * shown the pick-your-zone banner. It is also why {@code ca}, {@code eu} and {@code gl} are
     * missing — all three imply Spain.
     */
    private static final Map<String, String> LIKELY_REGION = Map.ofEntries(
            Map.entry("af", "ZA"),
            Map.entry("am", "ET"),
            Map.entry("ar", "EG"),
            Map.entry("as", "IN"),
            Map.entry("az", "AZ"),
            Map.entry("be", "BY"),
            Map.entry("bg", "BG"),
            Map.entry("bn", "BD"),
            Map.entry("bs", "BA"),
            Map.entry("cs", "CZ"),
            Map.entry("cy", "GB"),
            Map.entry("da", "DK"),
            Map.entry("de", "DE"),
            Map.entry("dv", "MV"),
            Map.entry("el", "GR"),
            Map.entry("et", "EE"),
            Map.entry("fa", "IR"),
            Map.entry("fi", "FI"),
            Map.entry("fil", "PH"),
            Map.entry("fo", "FO"),
            Map.entry("fr", "FR"),
            Map.entry("ga", "IE"),
            Map.entry("gu", "IN"),
            Map.entry("he", "IL"),
            Map.entry("hi", "IN"),
            Map.entry("hr", "HR"),
            Map.entry("hu", "HU"),
            Map.entry("hy", "AM"),
            Map.entry("is", "IS"),
            Map.entry("it", "IT"),
            Map.entry("iw", "IL"),
            Map.entry("ja", "JP"),
            Map.entry("ka", "GE"),
            Map.entry("km", "KH"),
            Map.entry("kn", "IN"),
            Map.entry("ko", "KR"),
            Map.entry("ky", "KG"),
            Map.entry("lb", "LU"),
            Map.entry("lo", "LA"),
            Map.entry("lt", "LT"),
            Map.entry("lv", "LV"),
            Map.entry("mk", "MK"),
            Map.entry("ml", "IN"),
            Map.entry("mr", "IN"),
            Map.entry("mt", "MT"),
            Map.entry("my", "MM"),
            Map.entry("nb", "NO"),
            Map.entry("ne", "NP"),
            Map.entry("nl", "NL"),
            Map.entry("nn", "NO"),
            Map.entry("no", "NO"),
            Map.entry("or", "IN"),
            Map.entry("pa", "IN"),
            Map.entry("pl", "PL"),
            Map.entry("ps", "AF"),
            Map.entry("ro", "RO"),
            Map.entry("si", "LK"),
            Map.entry("sk", "SK"),
            Map.entry("sl", "SI"),
            Map.entry("so", "SO"),
            Map.entry("sq", "AL"),
            Map.entry("sr", "RS"),
            Map.entry("sv", "SE"),
            Map.entry("te", "IN"),
            Map.entry("tg", "TJ"),
            Map.entry("th", "TH"),
            Map.entry("tk", "TM"),
            Map.entry("tl", "PH"),
            Map.entry("tr", "TR"),
            Map.entry("uk", "UA"),
            Map.entry("ur", "PK"),
            Map.entry("uz", "UZ"),
            Map.entry("vi", "VN"),
            Map.entry("zh", "CN"),
            Map.entry("zu", "ZA")
    );

    /**
     * The zone this locale implies, or empty when it implies none.
     *
     * <p>Empty is a real answer and the caller must handle it: it is what puts the "we could not
     * work out your time zone" banner on the page. Locales that land here are the ones that
     * genuinely carry no clock information — a bare {@code en}, or any region that spans several
     * zones.
     */
    public static Optional<ZoneId> guess(Locale locale) {
        if (locale == null) return Optional.empty();
        var country = locale.getCountry();
        if (country == null || country.isBlank()) {
            country = LIKELY_REGION.get(locale.getLanguage().toLowerCase(Locale.ROOT));
        }
        if (country == null || country.isBlank()) return Optional.empty();
        var zone = COUNTRY_ZONES.get(country.toUpperCase(Locale.ROOT));
        if (zone == null) return Optional.empty();
        // Guard against a tzdb release retiring a name this table still carries: a missing zone is
        // "no guess", never an exception on a page render.
        return ZoneId.getAvailableZoneIds().contains(zone)
                ? Optional.of(ZoneId.of(zone))
                : Optional.empty();
    }

    /**
     * Visible for tests: every zone name the table maps to.
     *
     * <p>{@link #forCountry} and {@link #guess} both drop a name tzdb does not know, which is right
     * at runtime and useless as a check — a typo would read as "no guess for that country" and
     * never be noticed. The test asserts against the raw values instead.
     */
    static java.util.Collection<String> mappedZoneNames() {
        return COUNTRY_ZONES.values();
    }

    /** The zone a country keeps, for tests and for anything that has a region but no locale. */
    public static Optional<ZoneId> forCountry(String isoCountry) {
        if (isoCountry == null || isoCountry.isBlank()) return Optional.empty();
        var zone = COUNTRY_ZONES.get(isoCountry.trim().toUpperCase(Locale.ROOT));
        return zone == null || !ZoneId.getAvailableZoneIds().contains(zone)
                ? Optional.empty()
                : Optional.of(ZoneId.of(zone));
    }
}
