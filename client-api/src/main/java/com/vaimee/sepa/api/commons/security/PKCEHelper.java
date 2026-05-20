package com.vaimee.sepa.api.commons.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class PKCEHelper {

	private static final String VERIFIER_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
	private static final int VERIFIER_LENGTH = 128;
	private static final int STATE_LENGTH = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private PKCEHelper() {
	}

	public static String generateCodeVerifier() {
		StringBuilder sb = new StringBuilder(VERIFIER_LENGTH);
		for (int i = 0; i < VERIFIER_LENGTH; i++) {
			sb.append(VERIFIER_CHARSET.charAt(RANDOM.nextInt(VERIFIER_CHARSET.length())));
		}
		return sb.toString();
	}

	public static String generateCodeChallenge(String codeVerifier) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

	public static String generateState() {
		byte[] bytes = new byte[STATE_LENGTH];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
