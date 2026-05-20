/* This class implements the TLS 1.0 security mechanism 
 * 
 * Author: Luca Roffia (luca.roffia@unibo.it)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vaimee.sepa.api.commons.security;

import java.awt.Desktop;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;

import org.apache.http.impl.client.CloseableHttpClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.vaimee.sepa.api.commons.exceptions.SEPAPropertiesException;
import com.vaimee.sepa.api.commons.exceptions.SEPASecurityException;
import com.vaimee.sepa.api.commons.response.ErrorResponse;
import com.vaimee.sepa.api.commons.response.JWTResponse;
import com.vaimee.sepa.api.commons.response.RegistrationResponse;
import com.vaimee.sepa.api.commons.response.Response;
import com.vaimee.sepa.api.commons.security.OAuthProperties.OAUTH_PROVIDER;
import com.vaimee.sepa.logging.Logging;

public class ClientSecurityManager implements Closeable {
	private final OAuthProperties oauthProperties;
	
	private final AuthenticationService oauth;
	
	public ClientSecurityManager(OAuthProperties oauthProp) throws SEPASecurityException {		
		oauthProperties = oauthProp;
		
		if (oauthProperties.getProvider().equals(OAUTH_PROVIDER.SEPA)) oauth = new DefaultAuthenticationService(oauthProp);
		else oauth = new ZitadelAuthenticationService(oauthProp);
	}
	
	public SSLContext getSSLContext() throws SEPASecurityException {
		return oauth.getSSLContext();
	}
	
	public CloseableHttpClient getSSLHttpClient() {
		return oauth.getSSLHttpClient();
	}

	public Response registerClient(String client_id, String username,String initialAccessToken,int timeout) throws SEPASecurityException, SEPAPropertiesException {
		if (oauthProperties == null)
			throw new SEPAPropertiesException("Authorization properties are null");

		Response ret = oauth.registerClient(client_id,username, initialAccessToken,timeout);

		if (ret.isRegistrationResponse()) {
			RegistrationResponse reg = (RegistrationResponse) ret;
			oauthProperties.setCredentials(reg.getClientId(), reg.getClientSecret());
		} else {
			Logging.error(ret);
		}

		return ret;
	}

	public Response registerClient(String client_id,String username,String initialAccessToken) throws SEPASecurityException, SEPAPropertiesException {
		return registerClient(client_id,username,initialAccessToken, 5000);
	}

	public Response refreshToken(int timeout) throws SEPAPropertiesException, SEPASecurityException {
		if (!oauthProperties.isClientRegistered()) {
			return new ErrorResponse(401, "invalid_client", "Client is not registered");
		}

		Response ret = oauth.requestToken(oauthProperties.getBasicAuthorizationHeader(),timeout);

		if (ret.isJWTResponse()) {
			JWTResponse jwt = (JWTResponse) ret;

			Logging.debug("New token: " + jwt);

			oauthProperties.setJWT(jwt);
		} else {
			Logging.error("FAILED to refresh token " + new Date() + " Response: " + ret);
		}

		return ret;
	}
	
	public Response refreshToken() throws SEPAPropertiesException, SEPASecurityException {
		return refreshToken(5000);
	}

	public Response authenticateWithPKCE() throws SEPAPropertiesException, SEPASecurityException {
		if (!(oauth instanceof ZitadelAuthenticationService)) {
			return new ErrorResponse(400, "unsupported_flow", "PKCE flow requires ZitadelAuthenticationService");
		}
		if (oauthProperties.getAuthorizationEndpoint() == null) {
			return new ErrorResponse(400, "missing_config", "authorizationEndpoint not configured in OAuth properties");
		}
		if (oauthProperties.getRedirectUri() == null) {
			return new ErrorResponse(400, "missing_config", "redirectUri not configured in OAuth properties");
		}
		if (oauthProperties.getClientId() == null) {
			return new ErrorResponse(400, "missing_config", "client_id not configured in OAuth properties");
		}

		ZitadelAuthenticationService zitadelAuth = (ZitadelAuthenticationService) oauth;

		String codeVerifier = PKCEHelper.generateCodeVerifier();
		String codeChallenge = PKCEHelper.generateCodeChallenge(codeVerifier);
		String state = PKCEHelper.generateState();

		oauthProperties.setCodeVerifier(codeVerifier);

		String redirectUri = oauthProperties.getRedirectUri();
		java.net.URI redirectUriParsed;
		try {
			redirectUriParsed = new java.net.URI(redirectUri);
		} catch (Exception e) {
			return new ErrorResponse(500, "invalid_redirect_uri", "redirectUri is not a valid URI: " + redirectUri);
		}
		String redirectPath = redirectUriParsed.getPath();
		int port = redirectUriParsed.getPort();
		if (port == -1) {
			port = redirectUriParsed.getScheme().equals("https") ? 443 : 80;
		}

		CountDownLatch callbackLatch = new CountDownLatch(1);
		AtomicReference<String> authCode = new AtomicReference<>(null);
		AtomicReference<String> errorResult = new AtomicReference<>(null);

		HttpServer httpServer;
		try {
			httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		} catch (IOException e) {
			return new ErrorResponse(500, "port_in_use",
					"La porta " + port + " e occupata. Chiudi i processi Java rimasti appesi in background.");
		}

		httpServer.createContext(redirectPath, new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				String query = exchange.getRequestURI().getQuery();
				String code = null;
				String returnedState = null;
				String error = null;

				if (query != null) {
					for (String param : query.split("&")) {
						String[] kv = param.split("=", 2);
						if (kv.length == 2) {
							if ("code".equals(kv[0])) {
								code = java.net.URLDecoder.decode(kv[1], "UTF-8");
							} else if ("state".equals(kv[0])) {
								returnedState = java.net.URLDecoder.decode(kv[1], "UTF-8");
							} else if ("error".equals(kv[0])) {
								error = java.net.URLDecoder.decode(kv[1], "UTF-8");
							}
						}
					}
				}

				String responseHtml;
				if (error != null) {
					errorResult.set("Zitadel returned error: " + error);
					responseHtml = buildHtmlPage("Authentication Failed", "Zitadel returned an error: " + error + ". Please close this window and try again.");
				} else if (state.equals(returnedState)) {
					authCode.set(code);
					responseHtml = buildHtmlPage("Authentication Complete", "Authentication successful! You may close this window and return to the SEPA Dashboard.");
				} else {
					errorResult.set("State mismatch: expected=" + state + " got=" + returnedState);
					responseHtml = buildHtmlPage("Authentication Failed", "State mismatch detected. This could be a CSRF attack. Please close this window and try again.");
				}

				exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
				byte[] bytes = responseHtml.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, bytes.length);
				OutputStream os = exchange.getResponseBody();
				os.write(bytes);
				os.close();

				callbackLatch.countDown();
			}
		});

		httpServer.setExecutor(null);
		httpServer.start();

		try {
			String authorizeUrl = oauthProperties.getAuthorizationEndpoint()
					+ "?client_id=" + URLEncoder.encode(oauthProperties.getClientId(), "UTF-8")
					+ "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
					+ "&response_type=code"
					+ "&scope=" + URLEncoder.encode("openid profile email", "UTF-8")
					+ "&code_challenge=" + URLEncoder.encode(codeChallenge, "UTF-8")
					+ "&code_challenge_method=S256"
					+ "&state=" + URLEncoder.encode(state, "UTF-8");

			Logging.log("oauth", "PKCE authorize URL: " + authorizeUrl);

			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(new URI(authorizeUrl));
			} else {
				httpServer.stop(0);
				return new ErrorResponse(500, "browser_error", "Desktop browse not supported on this platform");
			}

			boolean completed = callbackLatch.await(300, TimeUnit.SECONDS);

			if (!completed) {
				return new ErrorResponse(408, "timeout", "Authentication timed out after 300 seconds. Please try again.");
			}

			if (errorResult.get() != null) {
				return new ErrorResponse(400, "auth_error", errorResult.get());
			}

			String code = authCode.get();
			if (code == null || code.isEmpty()) {
				return new ErrorResponse(400, "missing_code", "No authorization code received from Zitadel");
			}

			Response tokenResponse = zitadelAuth.exchangeCodeForToken(code, codeVerifier, redirectUri);

			if (tokenResponse.isJWTResponse()) {
				JWTResponse jwt = (JWTResponse) tokenResponse;
				Logging.debug("PKCE token obtained: " + jwt);
				oauthProperties.setJWT(jwt);
			} else {
				Logging.error("PKCE token exchange failed: " + tokenResponse);
			}

			return tokenResponse;

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new ErrorResponse(500, "interrupted", "Authentication was interrupted");
		} catch (Exception e) {
			Logging.error("PKCE flow error: " + e.getMessage());
			return new ErrorResponse(500, "pkce_error", e.getMessage());
		} finally {
			httpServer.stop(0);
			oauthProperties.setCodeVerifier(null);
		}
	}

	private static String buildHtmlPage(String title, String message) {
		return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>" + title
				+ "</title><style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#f0f2f5;color:#1a1a2e;text-align:center;}"
				+ ".card{background:#fff;padding:48px 64px;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,0.1);max-width:480px;}"
				+ "h1{font-size:24px;margin-bottom:16px;}p{font-size:16px;color:#555;}"
				+ "</style></head><body><div class=\"card\"><h1>" + title
				+ "</h1><p>" + message + "</p></div></body></html>";
	}

	@Override
	public void close() throws IOException {
		oauth.close();
	}
}
