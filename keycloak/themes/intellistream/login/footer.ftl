<#--
  The one template this theme overrides.

  Keycloak ships base/login/footer.ftl as an empty macro whose entire comment reads "you can
  override this file in your custom theme to declare a custom login footer element" — it is the
  designated extension point, it has no logic that can fall behind, and no upgrade can silently
  break it. There is no CSS route to the same result: ::after content on the card would put a
  real link inside a pseudo-element, where it is neither focusable nor clickable.

  Two things go in it. The reassurance that this is a self-hosted deployment (the credentials
  land in the operator's own Keycloak, not a SaaS), and an escape hatch back to the application
  for anyone who arrived at a sign-in they did not mean to start. `client.baseUrl` is only set
  when the realm's client declares a Home URL, so the link is conditional; without it the line
  is just the reassurance.

  The string lives in messages/messages_en.properties beside this file rather than inline, so
  it is translatable like every other string on the page. Theme message bundles are MERGED down
  the parent chain (unlike `styles`), so that file adds one key and inherits the other ~400.
-->
<#macro content>
<div id="kc-footer" class="ichat-footer">
    <span>${msg("ichatSelfHostedNote")}</span>
    <#if client?? && client.baseUrl?has_content>
        <span class="ichat-footer-sep" aria-hidden="true">&middot;</span>
        <a href="${client.baseUrl}">${msg("backToApplication")}</a>
    </#if>
</div>
</#macro>
