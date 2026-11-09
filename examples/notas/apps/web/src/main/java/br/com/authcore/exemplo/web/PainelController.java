package br.com.authcore.exemplo.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A página que explica o auth com os dados reais do login: o que o navegador tem, o que este servidor guardou, o que
 * há dentro de cada token e o que a API extrai deles. Os botões fazem, ao vivo, o que o auth oferece hoje: renovação,
 * introspecção, revogação e logout.
 */
@Controller
class PainelController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String NO_ANSWER = "Não houve resposta: o serviço está fora do ar ou demorou demais. "
            + "Nada mudou; tente de novo quando ele voltar.";
    /** Erros da renovação em que o refresh token não serve mais, ou a sessão não tem um: só um novo login resolve. */
    private static final Set<String> RELOGIN_ERRORS = Set.of(OAuth2ErrorCodes.INVALID_GRANT,
            OAuth2ErrorCodes.INVALID_TOKEN, "client_authorization_required");

    private final AuthEndpoints auth;
    private final ApiCalls api;
    private final OAuth2AuthorizedClientRepository clients;
    private final JsonMapper json;
    private final ZoneId zone;
    private final String authPublicUrl;
    private final String webPublicUrl;
    private final String apiUrl;

    PainelController(AuthEndpoints auth, ApiCalls api, OAuth2AuthorizedClientRepository clients, JsonMapper json,
                     @Value("${app.zone}") ZoneId zone, @Value("${app.auth.public-url}") String authPublicUrl,
                     @Value("${app.public-url}") String webPublicUrl, @Value("${app.api.public-url}") String apiUrl) {
        this.auth = auth;
        this.api = api;
        this.clients = clients;
        this.json = json;
        this.zone = zone;
        this.authPublicUrl = authPublicUrl;
        this.webPublicUrl = webPublicUrl;
        this.apiUrl = apiUrl;
    }

    /**
     * Resultado de uma chamada, já formatado para a página.
     *
     * @param plain o que aconteceu, em palavras simples
     */
    record Call(String title, String plain, String request, int status, String body) {
    }

    @GetMapping("/")
    String inicio(Authentication authentication, Model model) {
        if (authentication != null && authentication.isAuthenticated()) {
            return "redirect:/painel";
        }
        model.addAttribute("authUrl", authPublicUrl);
        model.addAttribute("webUrl", webPublicUrl);
        return "inicio";
    }

    @GetMapping("/painel")
    String painel(@RegisteredOAuth2AuthorizedClient("notas") OAuth2AuthorizedClient client,
                  @AuthenticationPrincipal OidcUser user, HttpServletRequest request, Model model) {
        OAuth2AccessToken accessToken = client.getAccessToken();
        OAuth2RefreshToken refreshToken = client.getRefreshToken();
        String bearer = accessToken.getTokenValue();

        model.addAttribute("email", user.getEmail());
        model.addAttribute("clientId", client.getClientRegistration().getClientId());
        model.addAttribute("authUrl", authPublicUrl);
        model.addAttribute("apiUrl", apiUrl);
        model.addAttribute("webUrl", webPublicUrl);

        // O que o navegador tem: só cookies. O valor do NOTASWEB é o id da sessão, e nada mais.
        model.addAttribute("sessionId", request.getSession().getId());
        model.addAttribute("cookies", request.getCookies() == null ? List.of()
                : Arrays.stream(request.getCookies()).map(Cookie::getName).distinct().toList());

        // O que este servidor guardou na sessão: os três tokens.
        model.addAttribute("access", JwtView.of(bearer, json, zone));
        model.addAttribute("accessIssuedAt", time(accessToken.getIssuedAt()));
        model.addAttribute("accessExpiresAt", time(accessToken.getExpiresAt()));
        model.addAttribute("accessScopes", accessToken.getScopes());
        model.addAttribute("idToken", JwtView.of(user.getIdToken().getTokenValue(), json, zone));
        model.addAttribute("refreshToken", refreshToken == null ? null : refreshToken.getTokenValue());
        model.addAttribute("authorities", user.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .sorted().toList());

        // O que a outra ponta (a API) faz com o token, e o que o servidor de autorização devolve sobre ele.
        model.addAttribute("apiCalls", List.of(
                call("Quem sou eu? (GET /api/v1/eu)",
                        "A API leu o token, achou a sua conta pelo sub e respondeu com os dados dela.",
                        "Authorization: Bearer <access token>", api.get("/api/v1/eu", bearer)),
                call("Minhas notas (GET /api/v1/notas)",
                        "Só as suas: a API filtra pela conta que veio do token.",
                        "Authorization: Bearer <access token>", api.get("/api/v1/notas", bearer)),
                call("Minhas notas, sem token",
                        "Sem token, a API nem chega a procurar as notas: responde 401 (não autenticado).",
                        "(sem o cabeçalho Authorization)", api.get("/api/v1/notas", null))));
        model.addAttribute("userInfo", call("GET /userinfo",
                "O servidor de autorização devolve os dados do dono do token.",
                "Authorization: Bearer <access token>", auth.userInfo(bearer)));
        model.addAttribute("jwks", call("GET /oauth2/jwks", "As chaves públicas, que qualquer um pode baixar.",
                "(público)", auth.jwks()));
        return "painel";
    }

    @PostMapping("/painel/renovar")
    String renovar(@RegisteredOAuth2AuthorizedClient("notas") OAuth2AuthorizedClient client,
                   Authentication authentication, HttpServletRequest request, HttpServletResponse response,
                   RedirectAttributes flash) {
        if (client.getRefreshToken() == null) {
            flash.addFlashAttribute("result", new Call("Renovação", "Esta sessão não tem refresh token.", "", 0, ""));
            return "redirect:/painel#acoes";
        }
        String before = JwtView.of(client.getAccessToken().getTokenValue(), json, zone).claims().stream()
                .filter(claim -> claim.name().equals("jti")).findFirst().map(JwtView.Claim::value).orElse("?");
        AuthEndpoints.Response answer = auth.refresh(client.getClientRegistration(),
                client.getRefreshToken().getTokenValue());
        String note = "";
        if (answer.status() == 200) {
            JsonNode body = json.readTree(answer.body());
            Instant now = Instant.now();
            Set<String> scopes = new LinkedHashSet<>(List.of(body.path("scope").asString("").split(" ")));
            var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, body.path("access_token").asString(),
                    now, now.plusSeconds(body.path("expires_in").asLong()), scopes);
            String refreshValue = body.path("refresh_token").asString(client.getRefreshToken().getTokenValue());
            var refresh = new OAuth2RefreshToken(refreshValue, now);
            clients.saveAuthorizedClient(new OAuth2AuthorizedClient(client.getClientRegistration(),
                    client.getPrincipalName(), access, refresh), authentication, request, response);
            String after = JwtView.of(access.getTokenValue(), json, zone).claims().stream()
                    .filter(claim -> claim.name().equals("jti")).findFirst().map(JwtView.Claim::value).orElse("?");
            note = "jti antes: " + before + "\njti depois: " + after + "\nrefresh token "
                    + (refreshValue.equals(client.getRefreshToken().getTokenValue())
                    ? "igual (o cliente notas-web não rotaciona: reuse-refresh-tokens)" : "novo") + "\n\n";
        }
        flash.addFlashAttribute("result", new Call("Renovar o access token",
                !answer.answered() ? NO_ANSWER : answer.status() == 200
                        ? "Esta aplicação trocou o refresh token por um access token novinho, sem pedir a sua senha. O "
                        + "token novo tem outro número de série (jti) e vale mais 15 minutos."
                        : "O servidor recusou a renovação. Se você revogou o refresh token, é isso mesmo: ele não serve "
                        + "mais, e para ter um token novo é preciso entrar de novo.",
                "POST /oauth2/token com grant_type=refresh_token e o refresh token, autenticado com o segredo "
                        + "do cliente (Basic notas-web:<segredo>)",
                answer.status(), note + pretty(answer.body())));
        return "redirect:/painel#acoes";
    }

    @PostMapping("/painel/introspectar")
    String introspectar(@RegisteredOAuth2AuthorizedClient("notas") OAuth2AuthorizedClient client,
                        @RequestParam String alvo, RedirectAttributes flash) {
        boolean refresh = alvo.equals("refresh") && client.getRefreshToken() != null;
        String token = refresh ? client.getRefreshToken().getTokenValue() : client.getAccessToken().getTokenValue();
        AuthEndpoints.Response answer = auth.introspect(client.getClientRegistration(), token);
        flash.addFlashAttribute("result", new Call("Perguntar ao servidor sobre o "
                + (refresh ? "refresh token" : "access token"),
                !answer.answered() ? NO_ANSWER : "\"active\": true quer dizer que o servidor reconhece o token e ele ainda vale. "
                        + (refresh ? "Repare que o refresh token, que não tem nada escrito dentro, só pode ser "
                        + "\"lido\" assim: perguntando a quem o emitiu."
                        : "A API não faz esta pergunta a cada requisição; ela confere a assinatura sozinha."),
                "POST /oauth2/introspect com o token, autenticado com o segredo do cliente", answer.status(),
                pretty(answer.body())));
        return "redirect:/painel#acoes";
    }

    @PostMapping("/painel/revogar")
    String revogar(@RegisteredOAuth2AuthorizedClient("notas") OAuth2AuthorizedClient client,
                   RedirectAttributes flash) {
        if (client.getRefreshToken() == null) {
            flash.addFlashAttribute("result", new Call("Revogação", "Esta sessão não tem refresh token.", "", 0, ""));
            return "redirect:/painel#acoes";
        }
        AuthEndpoints.Response revoked = auth.revoke(client.getClientRegistration(),
                client.getRefreshToken().getTokenValue());
        if (!revoked.answered()) {
            flash.addFlashAttribute("result", new Call("Revogar o refresh token", NO_ANSWER, "", 0, ""));
            return "redirect:/painel#acoes";
        }
        AuthEndpoints.Response access = auth.introspect(client.getClientRegistration(),
                client.getAccessToken().getTokenValue());
        AuthEndpoints.Response stillAccepted = api.get("/api/v1/eu", client.getAccessToken().getTokenValue());
        flash.addFlashAttribute("result", new Call("Revogar o refresh token",
                "O servidor cancelou a sua autorização: perguntado agora, ele diz que o access token não vale mais "
                        + "(active: false). Mas a API respondeu " + stillAccepted.status() + " com o mesmo token, porque"
                        + " ela não pergunta ao servidor: só confere o lacre (a assinatura), e o lacre continua "
                        + "perfeito. O token vai funcionar na API até expirar, em no máximo 15 minutos. O que já parou "
                        + "de funcionar é a renovação: tente o botão Renovar.",
                "POST /oauth2/revoke com o refresh token, autenticado com o segredo do cliente", revoked.status(),
                "Introspecção do access token depois da revogação:\n" + pretty(access.body()) + "\n\n"
                        + "API com o mesmo access token: " + stillAccepted.status() + "\n"
                        + pretty(stillAccepted.body())));
        return "redirect:/painel#acoes";
    }

    @PostMapping("/painel/nota")
    String nota(@RegisteredOAuth2AuthorizedClient("notas") OAuth2AuthorizedClient client, @RequestParam String titulo,
                RedirectAttributes flash) {
        AuthEndpoints.Response answer = api.createNote(client.getAccessToken().getTokenValue(), titulo);
        flash.addFlashAttribute("result", new Call("Criar uma nota na API",
                !answer.answered() ? NO_ANSWER
                        : answer.status() < 400 ? "A API recebeu o token, descobriu que é você e gravou a nota na sua conta."
                        : "A API recusou o pedido. A resposta abaixo diz por quê, no campo code.",
                "POST /api/v1/notas com Authorization: Bearer <access token> e {\"titulo\": \"" + titulo + "\"}",
                answer.status(), pretty(answer.body())));
        return "redirect:/painel#api";
    }

    /**
     * Sai das duas sessões: revoga o refresh token, encerra a sessão daqui e manda o navegador ao logout do servidor
     * (OpenID Connect RP-Initiated Logout), que encerra a sessão de lá (AUTHSESSION) e volta para cá.
     */
    @PostMapping("/sair")
    String sair(@RegisteredOAuth2AuthorizedClient("notas") OAuth2AuthorizedClient client,
                @AuthenticationPrincipal OidcUser user, Authentication authentication, HttpServletRequest request,
                HttpServletResponse response) {
        if (client.getRefreshToken() != null) {
            auth.revoke(client.getClientRegistration(), client.getRefreshToken().getTokenValue());
        }
        String idToken = user.getIdToken().getTokenValue();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return "redirect:" + UriComponentsBuilder.fromUriString(authPublicUrl + "/connect/logout")
                .queryParam("id_token_hint", idToken)
                .queryParam("post_logout_redirect_uri", webPublicUrl + "/")
                .build().toUriString();
    }

    /** Sem resposta, a explicação de sucesso não vale: a página diz que o serviço não respondeu. */
    private Call call(String title, String plain, String request, AuthEndpoints.Response response) {
        return new Call(title, response.answered() ? plain : NO_ANSWER, request, response.status(),
                pretty(response.body()));
    }

    /**
     * A renovação automática do access token falhou ao montar a página (o Spring Security renova sozinho quando o
     * token vence). Se o servidor recusou o refresh token, por exemplo depois do botão "Revogar", o login recomeça:
     * com a sessão do servidor de autorização ainda aberta, ele é silencioso. Se o servidor não respondeu, a sessão
     * daqui continua e a página avisa.
     */
    @ExceptionHandler(OAuth2AuthorizationException.class)
    Object renovacaoFalhou(OAuth2AuthorizationException ex) {
        String error = ex.getError().getErrorCode();
        if (RELOGIN_ERRORS.contains(error)) {
            return "redirect:/oauth2/authorization/notas";
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body("O servidor de autorização não respondeu ao renovar o seu access token (" + error
                        + "). A sua sessão continua: recarregue a página em instantes.");
    }

    private String pretty(String body) {
        if (body == null || body.isBlank()) {
            return "(sem corpo)";
        }
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(body));
        } catch (RuntimeException notJson) {
            return body;
        }
    }

    private String time(Instant instant) {
        return instant == null ? "—" : TIME.format(instant.atZone(zone));
    }
}
