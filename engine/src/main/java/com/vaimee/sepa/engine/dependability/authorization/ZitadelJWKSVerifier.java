package com.vaimee.sepa.engine.dependability.authorization;

import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.time.Instant;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

import com.vaimee.sepa.logging.Logging;

public class ZitadelJWKSVerifier {

	private final String jwksUri;
	private final long cacheTtlSeconds;

	private volatile JWKSet cachedJwkSet;
	private volatile Instant lastFetch;
	private final ReadWriteLock lock = new ReentrantReadWriteLock();

	public ZitadelJWKSVerifier(String jwksUri) {
		this(jwksUri, 3600);
	}

	public ZitadelJWKSVerifier(String jwksUri, long cacheTtlSeconds) {
		this.jwksUri = jwksUri;
		this.cacheTtlSeconds = cacheTtlSeconds;
	}

	public boolean verify(SignedJWT signedJWT) throws JOSEException, ParseException {
		String kid = signedJWT.getHeader().getKeyID();

		if (kid != null) {
			JWK key = getKey(kid);
			if (key != null && key instanceof RSAKey) {
				JWSVerifier verifier = new RSASSAVerifier((RSAKey) key);
				return signedJWT.verify(verifier);
			}
		}

		JWKSet jwkSet = getJwkSet();
		if (jwkSet == null) {
			Logging.log("oauth", "JWKS not available, attempting refresh");
			jwkSet = refreshJwkSet();
		}

		if (jwkSet == null) {
			Logging.log("oauth", "No JWKS available to verify token");
			return false;
		}

		for (JWK key : jwkSet.getKeys()) {
			if (key instanceof RSAKey) {
				try {
					JWSVerifier verifier = new RSASSAVerifier((RSAKey) key);
					if (signedJWT.verify(verifier)) {
						return true;
					}
				} catch (JOSEException e) {
					continue;
				}
			}
		}

		return false;
	}

	public JWK getKey(String kid) {
		JWKSet jwkSet = getJwkSet();
		if (jwkSet == null) {
			Logging.log("oauth", "JWKS not cached, fetching for kid: " + kid);
			jwkSet = refreshJwkSet();
		}

		if (jwkSet == null) {
			return null;
		}

		JWK key = jwkSet.getKeyByKeyId(kid);
		if (key == null) {
			Logging.log("oauth", "Key with kid '" + kid + "' not found in JWKS, refreshing");
			jwkSet = refreshJwkSet();
			if (jwkSet != null) {
				key = jwkSet.getKeyByKeyId(kid);
			}
		}

		return key;
	}

	public JWKSet getJwkSet() {
		lock.readLock().lock();
		try {
			if (cachedJwkSet != null && lastFetch != null) {
				if (Instant.now().isBefore(lastFetch.plusSeconds(cacheTtlSeconds))) {
					return cachedJwkSet;
				}
			}
			return cachedJwkSet;
		} finally {
			lock.readLock().unlock();
		}
	}

	public JWKSet refreshJwkSet() {
		lock.writeLock().lock();
		try {
			if (cachedJwkSet != null && lastFetch != null) {
				if (Instant.now().isBefore(lastFetch.plusSeconds(cacheTtlSeconds))) {
					return cachedJwkSet;
				}
			}

			Logging.log("oauth", "Fetching JWKS from: " + jwksUri);
			cachedJwkSet = JWKSet.load(new URL(jwksUri));
			lastFetch = Instant.now();
			Logging.log("oauth", "JWKS loaded successfully: " + cachedJwkSet.getKeys().size() + " keys");
			return cachedJwkSet;
		} catch (IOException | ParseException e) {
			Logging.error("Failed to fetch JWKS from " + jwksUri + ": " + e.getMessage());
			lastFetch = Instant.now();
			return cachedJwkSet;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void invalidateCache() {
		lock.writeLock().lock();
		try {
			cachedJwkSet = null;
			lastFetch = null;
		} finally {
			lock.writeLock().unlock();
		}
	}
}
