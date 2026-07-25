// De-risk the OIDC auth-code flow in pure Java: log in and print the app session cookie.
// Run: java benchmark/AuthProbe.java http://localhost:8080 alice alice
import java.net.*;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.regex.*;

public class AuthProbe {
    public static void main(String[] a) throws Exception {
        String base = a[0], user = a[1], pass = a[2];
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        // 1) kick off the OIDC login; HttpClient follows redirects to the Keycloak login page.
        var r1 = http.send(HttpRequest.newBuilder(URI.create(base + "/oauth2/authorization/keycloak")).GET().build(),
                BodyHandlers.ofString());
        String html = r1.body();
        System.out.println("login page status=" + r1.statusCode() + " finalUri=" + r1.uri());
        // 2) extract the login form action.
        var m = Pattern.compile("<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html);
        if (!m.find()) { System.out.println("NO FORM FOUND. First 600 chars:\n" + html.substring(0, Math.min(600, html.length()))); return; }
        String action = m.group(1).replace("&amp;", "&");
        System.out.println("form action=" + action);
        // 3) POST credentials; HttpClient follows the redirect back to the app callback → sets JSESSIONID.
        String form = "username=" + URLEncoder.encode(user, "UTF-8")
                + "&password=" + URLEncoder.encode(pass, "UTF-8") + "&credentialId=";
        var r2 = http.send(HttpRequest.newBuilder(URI.create(action))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                BodyHandlers.ofString());
        System.out.println("after-login status=" + r2.statusCode() + " finalUri=" + r2.uri());
        String jsession = null;
        for (var c : cookies.getCookieStore().getCookies()) {
            if (c.getName().equals("JSESSIONID")) jsession = c.getValue();
        }
        System.out.println("JSESSIONID=" + jsession);
        // 4) prove the session works against an authenticated API.
        var r3 = http.send(HttpRequest.newBuilder(URI.create(base + "/api/channels")).GET().build(), BodyHandlers.ofString());
        System.out.println("/api/channels status=" + r3.statusCode() + " body[0:120]=" + r3.body().substring(0, Math.min(120, r3.body().length())));
    }
}
