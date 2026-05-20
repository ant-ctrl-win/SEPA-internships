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

public class ZitadelAuthenticationService extends AuthenticationService {
	String registrationAccessToken;

	public ZitadelAuthenticationService(OAuthProperties oauthProp)
			throws SEPASecurityException {
		super(oauthProp);
	}

	@Override
	public Response registerClient(String client_id, String username, String initialAccessToken, int timeout) throws SEPASecurityException {
		return new ErrorResponse(501, "not_supported", "Dynamic client registration disabled. Configure client manually in Zitadel.");
	}

	public Response exchangeCodeForToken(String code, String codeVerifier, String redirectUri, int timeout) {
		Logging.log("oauth", "CODE_EXCHANGE: code=" + (code != null ? code.substring(0, Math.min(8, code.length())) + "..." : "null"));

		CloseableHttpResponse response = null;
		Logging.Timestamp start = new Logging.Timestamp();

		try {
			URI uri = new URI(oauthProperties.getTokenRequestUrl());
			HttpPost httpRequest = new HttpPost(uri);

			List<NameValuePair> params = new ArrayList<NameValuePair>();
			params.add(new BasicNameValuePair("grant_type", "authorization_code"));
			params.add(new BasicNameValuePair("client_id", oauthProperties.getClientId()));
			params.add(new BasicNameValuePair("code", code));
			params.add(new BasicNameValuePair("redirect_uri", redirectUri));
			params.add(new BasicNameValuePair("code_verifier", codeVerifier));
			UrlEncodedFormEntity body = new UrlEncodedFormEntity(params, Charset.forName("UTF-8"));
			httpRequest.setEntity(body);
			httpRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");

			RequestConfig requestConfig = RequestConfig.custom().setSocketTimeout(timeout).setConnectTimeout(timeout)
					.build();
			httpRequest.setConfig(requestConfig);

			try {
				response = httpClient.execute(httpRequest);
			} catch (Exception e) {
				ErrorResponse err = new ErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getClass().getName(), e.getMessage());
				Logging.error(err);
				return err;
			}

			Logging.log("oauth", "CODE_EXCHANGE Response: " + response);
			HttpEntity entity = response.getEntity();
			String jsonResponse = EntityUtils.toString(entity, Charset.forName("UTF-8"));
			EntityUtils.consume(entity);

			JsonObject json = new Gson().fromJson(jsonResponse, JsonObject.class);

			if (json.has("error")) {
				Logging.logTiming("CODE_EXCHANGE", start, new Logging.Timestamp());
				ErrorResponse error = new ErrorResponse(response.getStatusLine().getStatusCode(), "code_exchange",
						json.get("error").getAsString());
				return error;
			}

			Logging.logTiming("CODE_EXCHANGE", start, new Logging.Timestamp());
			return new JWTResponse(json);
		} catch (Exception e) {
			Logging.error(e.getMessage());
			Logging.logTiming("CODE_EXCHANGE", start, new Logging.Timestamp());
			return new ErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, "Exception", e.getMessage());
		} finally {
			try {
				if (response != null)
					response.close();
			} catch (IOException e) {
				Logging.error(e.getMessage());
				Logging.logTiming("CODE_EXCHANGE", start, new Logging.Timestamp());
				return new ErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, "IOException", e.getMessage());
			}
		}
	}

	public Response exchangeCodeForToken(String code, String codeVerifier, String redirectUri) {
		return exchangeCodeForToken(code, codeVerifier, redirectUri, 10000);
	}

	@Override
	public Response requestToken(String authorization, int timeout) {
		Logging.log("oauth","TOKEN_REQUEST: " + authorization);

		CloseableHttpResponse response = null;
		Logging.Timestamp start = new Logging.Timestamp();

		try {
			URI uri = new URI(oauthProperties.getTokenRequestUrl());
			HttpPost httpRequest = new HttpPost(uri);

			List<NameValuePair> params = new ArrayList<NameValuePair>();
			params.add(new BasicNameValuePair("grant_type", "client_credentials"));
			params.add(new BasicNameValuePair("scope", "openid profile"));
			UrlEncodedFormEntity body = new UrlEncodedFormEntity(params, Charset.forName("UTF-8"));
			httpRequest.setEntity(body);
			httpRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");

			String clientId = java.net.URLEncoder.encode(oauthProperties.getClientId(), "UTF-8");
			String clientSecret = java.net.URLEncoder.encode(oauthProperties.getClientSecret(), "UTF-8");
			String authString = clientId + ":" + clientSecret;
			String encodedAuth = "Basic " + java.util.Base64.getEncoder().encodeToString(authString.getBytes(Charset.forName("UTF-8")));
			httpRequest.setHeader("Authorization", encodedAuth);

			RequestConfig requestConfig = RequestConfig.custom().setSocketTimeout(timeout).setConnectTimeout(timeout)
					.build();
			httpRequest.setConfig(requestConfig);

			try {
				response = httpClient.execute(httpRequest);
			} catch (Exception e) {
				ErrorResponse err = new ErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getClass().getName(), e.getMessage());
				Logging.error(err);
				return err;
			}

			Logging.log("oauth","Response: " + response);
			HttpEntity entity = response.getEntity();
			String jsonResponse = EntityUtils.toString(entity, Charset.forName("UTF-8"));
			EntityUtils.consume(entity);

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
