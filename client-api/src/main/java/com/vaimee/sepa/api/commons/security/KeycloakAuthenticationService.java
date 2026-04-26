package com.vaimee.sepa.api.commons.security;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.vaimee.sepa.api.commons.exceptions.SEPASecurityException;
import com.vaimee.sepa.api.commons.response.ErrorResponse;
import com.vaimee.sepa.api.commons.response.JWTResponse;
import com.vaimee.sepa.api.commons.response.Response;
import com.vaimee.sepa.logging.Logging;

public class KeycloakAuthenticationService extends AuthenticationService {
	String registrationAccessToken;

	public KeycloakAuthenticationService(OAuthProperties oauthProp)
			throws SEPASecurityException {
		super(oauthProp);
	}

	/**
	 * Client Registration Request
	 * <p>
curl --location --request POST 'https://sepa.vaimee.it:8443/auth/realms/MONAS/clients-registrations/default' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICI4Y2E2ZGNiNC1jZmY5LTQzNGUtODNhNi05NTk4MzQ1NjUxZGMifQ.eyJleHAiOjAsImlhdCI6MTU5OTgwNTYzMywianRpIjoiMzNkZjRjZDYtMjJkZC00M2UxLWFmMzItYWE3NTMwMmJmZGUzIiwiaXNzIjoiaHR0cHM6Ly9zZXBhLnZhaW1lZS5pdDo4NDQzL2F1dGgvcmVhbG1zL01PTkFTIiwiYXVkIjoiaHR0cHM6Ly9zZXBhLnZhaW1lZS5pdDo4NDQzL2F1dGgvcmVhbG1zL01PTkFTIiwidHlwIjoiSW5pdGlhbEFjY2Vzc1Rva2VuIn0.edceIxjn2Fdc3NzXYIu--lWbDVBF0YXQfrUJ1R94myc' \
--data-raw '{"clientId":"sepatest_client","standardFlowEnabled" : false, "implicitFlowEnabled" : false, "authorizationServicesEnabled":true,"directAccessGrantsEnabled" : false, "serviceAccountsEnabled" : true, "publicClient":false, "protocol":"openid-connect","protocolMappers":[{"name":"hardcoded_username","protocol":"openid-connect","protocolMapper" : "oidc-hardcoded-claim-mapper","config" : {"claim.value":"sepatest","userinfo.token.claim":"false","id.token.claim":"false","access.token.claim":"true","claim.name":"preferred_username","jsonType.label":"String"}}]}'
	 */

	@Override
	public Response registerClient(String client_id, String username, String initialAccessToken, int timeout) throws SEPASecurityException {
		return new ErrorResponse(501, "not_supported", "Dynamic client registration disabled. Configure client manually in Zitadel.");
	}

	@Override
	public Response requestToken(String authorization, int timeout) {
		/*
		 * POST /auth/realms/demo/protocol/openid-connect/token Authorization: Basic
		 * cHJvZHVjdC1zYS1jbGllbnQ6cGFzc3dvcmQ= Content-Type:
		 * application/x-www-form-urlencoded
		 * 
		 * grant_type=client_credentials
		 **/
		Logging.log("oauth","TOKEN_REQUEST: " + authorization);

		CloseableHttpResponse response = null;
		Logging.Timestamp start = new Logging.Timestamp();

		try {
			URI uri = new URI(oauthProperties.getTokenRequestUrl());
			HttpPost httpRequest = new HttpPost(uri);

			// 1. Costruisci il Body (SOLO grant_type e scope)
			List<NameValuePair> params = new ArrayList<NameValuePair>();
			params.add(new BasicNameValuePair("grant_type", "client_credentials"));
			params.add(new BasicNameValuePair("scope", "openid profile"));
			UrlEncodedFormEntity body = new UrlEncodedFormEntity(params, Charset.forName("UTF-8"));
			httpRequest.setEntity(body);
			httpRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");

			// 2. Costruisci il Basic Auth Header corretto (URLEncoded prima del Base64)
			String clientId = java.net.URLEncoder.encode(oauthProperties.getClientId(), "UTF-8");
			String clientSecret = java.net.URLEncoder.encode(oauthProperties.getClientSecret(), "UTF-8");
			String authString = clientId + ":" + clientSecret;
			String encodedAuth = "Basic " + java.util.Base64.getEncoder().encodeToString(authString.getBytes(Charset.forName("UTF-8")));
			
			// 3. Imposta l'header (ignora il parametro 'authorization' originale)
			httpRequest.setHeader("Authorization", encodedAuth);

			System.out.println("=== NEW ZITADEL REQUEST ===");
			System.out.println("Header Auth: " + encodedAuth);
			System.out.println("Body: " + org.apache.http.util.EntityUtils.toString(body));
			System.out.println("===========================");

			// Set timeout
			RequestConfig requestConfig = RequestConfig.custom().setSocketTimeout(timeout).setConnectTimeout(timeout)
					.build();
			httpRequest.setConfig(requestConfig);

			try {
				response = httpClient.execute(httpRequest);
				// break;
			} catch (Exception e) {
				ErrorResponse err = new ErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getClass().getName(), e.getMessage());
				Logging.error(err);
				return err;
			}

			Logging.log("oauth","Response: " + response);
			HttpEntity entity = response.getEntity();
			String jsonResponse = EntityUtils.toString(entity, Charset.forName("UTF-8"));
			EntityUtils.consume(entity);

			// Parse response
			JsonObject json = new Gson().fromJson(jsonResponse,JsonObject.class);

			if (json.has("error")) {
				Logging.logTiming("TOKEN_REQUEST", start, new Logging.Timestamp());
				ErrorResponse error = new ErrorResponse(response.getStatusLine().getStatusCode(),"token_request",
						json.get("error").getAsString());
				return error;
			}

			return new JWTResponse(json);
		} catch (Exception e) {
			Logging.error(e.getMessage());
			Logging.logTiming("TOKEN_REQUEST", start, new Logging.Timestamp());
			return new ErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, "Exception", e.getMessage());
		} finally {
			try {
				if (response != null)
					response.close();
			} catch (IOException e) {
				Logging.error(e.getMessage());
				Logging.logTiming("TOKEN_REQUEST", start, new Logging.Timestamp());
				return new ErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, "IOException", e.getMessage());
			}
		}
	}
}