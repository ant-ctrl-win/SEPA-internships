package com.vaimee.sepa.tools.dashboard.utils;

import com.vaimee.sepa.api.commons.response.ErrorResponse;

public interface LoginListener {
	void onLogin(String id, String jwt);
	void onLoginError(ErrorResponse err);
	void onLoginClose();
	void onLoginTimeout();
}
