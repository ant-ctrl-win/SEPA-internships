package com.vaimee.sepa.engine.dependability.authorization;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpStatus;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.vaimee.sepa.api.commons.exceptions.SEPASecurityException;
import com.vaimee.sepa.api.commons.response.ErrorResponse;
import com.vaimee.sepa.api.commons.response.Response;
import com.vaimee.sepa.api.commons.security.ClientAuthorization;
import com.vaimee.sepa.api.commons.security.Credentials;
import com.vaimee.sepa.engine.dependability.authorization.identities.DigitalIdentity;
import com.vaimee.sepa.logging.Logging;

public class ZitadelSecurityManager extends SecurityManager {

	private static final String MOCK_PASSWORD = "MOCK_PASSWORD_123";

	private final ZitadelJWKSVerifier jwksVerifier;

	@Deprecated
	public ZitadelSecurityManager(SSLContext ssl, RSAKey key, LdapProperties prop, IsqlProperties isqlprop)
			throws SEPASecurityException {
		super(ssl, key, false);
		this.jwksVerifier = null;
	}

	public ZitadelSecurityManager(SSLContext ssl, String jwksUri) throws SEPASecurityException {
		super(ssl, null, false);
		this.jwksVerifier = new ZitadelJWKSVerifier(jwksUri);
		jwksVerifier.refreshJwkSet();
	}
	
	@Override
	public synchronized Response register(String uid) {
		return new ErrorResponse(HttpStatus.SC_UNAUTHORIZED, "not supported", "Implemented by Zitadel");
	}
	
	@Override
	public synchronized Response getToken(String encodedCredentials) {
		return new ErrorResponse(HttpStatus.SC_UNAUTHORIZED, "not supported", "Implemented by Zitadel");
	}

	@Override
	public synchronized ClientAuthorization validateToken(String accessToken) {
		Logging.log("oauth","VALIDATE TOKEN");

		SignedJWT signedJWT = null;
		try {
			signedJWT = SignedJWT.parse(accessToken);
		} catch (ParseException e) {
			Logging.log("oauth",e.getMessage());
			return new ClientAuthorization("invalid_request", "ParseException: " + e.getMessage());
		}

		if (jwksVerifier != null) {
			try {
				if (!jwksVerifier.verify(signedJWT)) {
					Logging.log("oauth","JWT signature not verified against Zitadel JWKS");
					return new ClientAuthorization("invalid_grant", "JWT signature not verified against Zitadel JWKS");
				}
			} catch (JOSEException | ParseException e) {
				Logging.log("oauth",e.getMessage());
				return new ClientAuthorization("invalid_grant", "Verification exception: " + e.getMessage());
			}
		} else {
			Logging.log("oauth","No JWKS verifier configured, using local engine key");
			try {
				if (!signedJWT.verify(verifier)) {
					Logging.log("oauth","Signed JWT not verified");
					return new ClientAuthorization("invalid_grant", "Signed JWT not verified");
				}
			} catch (JOSEException e) {
				Logging.log("oauth",e.getMessage());
				return new ClientAuthorization("invalid_grant", "JOSEException: " + e.getMessage());
			}
		}

		String uid;
		JWTClaimsSet claimsSet = null;
		try {
			claimsSet = signedJWT.getJWTClaimsSet();
			Logging.log("oauth",claimsSet.toString());
			uid = claimsSet.getStringClaim("preferred_username");
			if (uid == null) {
				Logging.log("oauth","<preferred_username> claim is null. Look for <username>");
				uid = claimsSet.getStringClaim("username");
				if (uid == null) {
					Logging.log("oauth","<username> claim is null. Look for <client_id>");
					uid = claimsSet.getStringClaim("client_id");
					if (uid == null) {
						Logging.log("oauth","USER ID not found...");
						uid = claimsSet.getStringClaim("sub");
						if (uid == null) {
							Logging.log("oauth","No identity claim found in token");
							return new ClientAuthorization("invalid_grant", "User identity claim not found");
						}
					}
				}
			}
			
			Logging.log("oauth","Subject: "+claimsSet.getSubject());
			Logging.log("oauth","Issuer: "+claimsSet.getIssuer());
			Logging.log("oauth","Username: "+uid);
		} catch (ParseException e) {
			Logging.error(e.getMessage());
			return new ClientAuthorization("invalid_grant", "ParseException. " + e.getMessage());
		}

		Date now = new Date();
		long nowUnixSeconds = (now.getTime() / 1000) * 1000;
		Date expiring = claimsSet.getExpirationTime();
		Date notBefore = claimsSet.getNotBeforeTime();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
		if (expiring.getTime() - nowUnixSeconds < 0) {
			Logging.log("oauth","Token is expired: " + sdf.format(claimsSet.getExpirationTime()) + " < "
					+ sdf.format(new Date(nowUnixSeconds)));

			return new ClientAuthorization("invalid_grant", "Token issued at " + sdf.format(claimsSet.getIssueTime())
					+ " is expired: " + sdf.format(claimsSet.getExpirationTime()) + " < " + sdf.format(now));
		}

		if (notBefore != null && nowUnixSeconds < notBefore.getTime()) {
			Logging.log("oauth","Token can not be used before: " + claimsSet.getNotBeforeTime());
			return new ClientAuthorization("invalid_grant",
					"Token can not be used before: " + claimsSet.getNotBeforeTime());
		}
		
		Credentials cred = null;
		try {
			cred = getEndpointCredentials(uid);
			Logging.log("oauth","Endpoint credentials: "+cred);
		} catch (SEPASecurityException e) {
			Logging.log("oauth","Failed to retrieve credentials (" + uid + ")");
			return new ClientAuthorization("invalid_grant", "Failed to get credentials (" + uid + ")");
		}

		return new ClientAuthorization(cred);
	}

	@Override
	public void addAuthorizedIdentity(DigitalIdentity identity) {
	}

	@Override
	public void removeAuthorizedIdentity(String uid) {
	}

	@Override
	public DigitalIdentity getIdentity(String uid) {
		return null;
	}

	@Override
	public boolean isAuthorized(String identity) {
		return false;
	}

	@Override
	public boolean isForTesting(String identity) {
		return false;
	}

	@Override
	public boolean storeCredentials(DigitalIdentity identity, String secret) {
		return false;
	}

	@Override
	public void removeCredentials(DigitalIdentity identity) {
	}

	@Override
	public boolean containsCredentials(String uid) {
		return false;
	}

	@Override
	public boolean checkCredentials(String uid, String secret) {
		return false;
	}

	@Override
	public Credentials getEndpointCredentials(String uid) throws SEPASecurityException {
		return new Credentials(uid, MOCK_PASSWORD);
	}

	@Override
	public void addJwt(String id, SignedJWT claims) {
	}

	@Override
	public boolean containsJwt(String id) {
		return false;
	}

	@Override
	public SignedJWT getJwt(String uid) {
		return null;
	}

	@Override
	public void removeJwt(String id) {
	}

	@Override
	public Date getTokenExpiringDate(String id) {
		return null;
	}

	@Override
	public long getTokenExpiringPeriod(String id) {
		return 0;
	}

	@Override
	public void setTokenExpiringPeriod(String id, long period) {
	}

	@Override
	public void setDeviceExpiringPeriod(long period) {
	}

	@Override
	public long getDeviceExpiringPeriod() {
		return 0;
	}

	@Override
	public void setApplicationExpiringPeriod(long period) {
	}

	@Override
	public long getApplicationExpiringPeriod() {
		return 0;
	}

	@Override
	public void setUserExpiringPeriod(long period) {
	}

	@Override
	public long getUserExpiringPeriod() {
		return 0;
	}

	@Override
	public void setDefaultExpiringPeriod(long period) {
	}

	@Override
	public long getDefaultExpiringPeriod() {
		return 0;
	}

	@Override
	public String getIssuer() {
		return null;
	}

	@Override
	public void setIssuer(String is) {
	}

}
