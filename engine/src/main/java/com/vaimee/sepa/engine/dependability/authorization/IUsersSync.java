package com.vaimee.sepa.engine.dependability.authorization;

import com.google.gson.JsonObject;

import com.vaimee.sepa.api.commons.exceptions.SEPASecurityException;

@Deprecated // Legacy LDAP user sync interface — replaced by ZitadelSecurityManager which does not use LDAP
public interface IUsersSync {
	public JsonObject sync() throws SEPASecurityException;
	public String getEndpointUsersPassword();
}
