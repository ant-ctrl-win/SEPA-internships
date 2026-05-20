package com.vaimee.sepa.tools.dashboard;

import java.net.URI;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vaimee.sepa.api.commons.exceptions.SEPAPropertiesException;
import com.vaimee.sepa.api.commons.exceptions.SEPASecurityException;
import com.vaimee.sepa.api.commons.response.JWTResponse;
import com.vaimee.sepa.api.commons.response.Response;
import com.vaimee.sepa.api.commons.security.ClientSecurityManager;
import com.vaimee.sepa.api.commons.security.OAuthProperties;
import com.vaimee.sepa.api.pattern.JSAP;

/**
 * Standalone PKCE test that bypasses the Dashboard entirely.
 * Reads zitadel-pkce.jsap directly, creates OAuthProperties + ClientSecurityManager,
 * and calls authenticateWithPKCE().
 */
public class StandalonePKCETest {

	public static void main(String[] args) throws Exception {
		System.out.println("=== SEPA PKCE Standalone Test ===");
		System.out.println();

		String jsapPath;
		if (args.length > 0) {
			jsapPath = args[0];
		} else {
			jsapPath = "tool-dashboard/src/main/resources/zitadel-pkce.jsap";
		}

		System.out.println("Loading JSAP: " + jsapPath);

		URI uri = new java.io.File(jsapPath).toURI();
		System.out.println("Resolved URI: " + uri);

		JSAP jsap = new JSAP(uri);

		System.out.println("isSecure: " + jsap.isSecure());
		System.out.println("oauth JSON: " + (jsap.getOauth() != null ? jsap.getOauth().toString() : "null"));

		OAuthProperties oauth = new OAuthProperties(jsap);

		System.out.println("provider: " + oauth.getProvider());
		System.out.println("authorizationEndpoint: " + oauth.getAuthorizationEndpoint());
		System.out.println("redirectUri: " + oauth.getRedirectUri());
		System.out.println("clientId: " + oauth.getClientId());
		System.out.println("isAuthorizationCodeFlow: " + oauth.isAuthorizationCodeFlow());

		if (!oauth.isAuthorizationCodeFlow()) {
			System.err.println("FATAL: isAuthorizationCodeFlow() returned false. Check the JSAP oauth block.");
			System.exit(1);
		}

		System.out.println();
		System.out.println("Creating ClientSecurityManager and starting PKCE flow...");
		System.out.println("A browser window should open. Complete the Zitadel login.");
		System.out.println();

		ClientSecurityManager sm = new ClientSecurityManager(oauth);

		Response response = sm.authenticateWithPKCE();

		if (response.isJWTResponse()) {
			JWTResponse jwt = (JWTResponse) response;
			System.out.println();
			System.out.println("=== AUTHENTICATION SUCCESSFUL ===");
			System.out.println("Access Token: " + jwt.getAccessToken());
			System.out.println("Token Type:   " + jwt.getTokenType());
			System.out.println("Expires In:   " + jwt.getExpiresIn() + " seconds");
		} else {
			System.err.println();
			System.err.println("=== AUTHENTICATION FAILED ===");
			System.err.println("Response: " + response);
			if (response.isError()) {
				com.vaimee.sepa.api.commons.response.ErrorResponse err =
					(com.vaimee.sepa.api.commons.response.ErrorResponse) response;
				System.err.println("Error code:   " + err.getStatusCode());
				System.err.println("Error:        " + err.getError());
				System.err.println("Description:  " + err.getErrorDescription());
			}
			System.exit(1);
		}
	}
}
