package com.vaimee.sepa.tools.dashboard.utils;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

import com.nimbusds.jwt.SignedJWT;

import com.vaimee.sepa.api.commons.response.ErrorResponse;
import com.vaimee.sepa.api.commons.response.JWTResponse;
import com.vaimee.sepa.api.commons.response.Response;
import com.vaimee.sepa.api.commons.security.ClientSecurityManager;
import com.vaimee.sepa.api.commons.security.OAuthProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class Login extends JDialog {
	private static final Logger logger = LogManager.getLogger();

	private static final long serialVersionUID = 544263217213326603L;
	private final JPanel contentPanel = new JPanel();

	private JButton btnCancel;

	private OAuthProperties oauth;
	private ClientSecurityManager sm;
	private LoginListener m_listener;
	private SwingWorker<Response, Void> authWorker;

	public Login(OAuthProperties oauth, LoginListener listener, JFrame parent) {
		this.oauth = oauth;
		m_listener = listener;

		setType(Type.POPUP);
		setModal(true);
		setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		if (oauth == null)
			throw new IllegalArgumentException("OAuthProperties is null");
		if (!oauth.isValid())
			throw new IllegalArgumentException("OAuthProperties is invalid: missing required fields (client_id, authorizationEndpoint, tokenRequest, redirectUri)");
		if (m_listener == null)
			throw new IllegalArgumentException("LoginListener is null");

		setResizable(false);
		setLocationRelativeTo(parent);

		addWindowListener(new WindowAdapter() {
			public void windowClosed(WindowEvent e) {
				logger.info("jdialog window closed");
			}

			public void windowClosing(WindowEvent e) {
				logger.info("jdialog window closing");
				cancelAuthentication();
				m_listener.onLoginClose();
			}
		});

		setTitle("SEPA Login — Zitadel");
		setBounds(100, 100, 420, 160);
		getContentPane().setLayout(new BorderLayout());
		contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
		getContentPane().add(contentPanel, BorderLayout.CENTER);
		GridBagLayout gbl_contentPanel = new GridBagLayout();
		gbl_contentPanel.columnWidths = new int[] { 420, 0 };
		gbl_contentPanel.rowHeights = new int[] { 0, 0, 0 };
		gbl_contentPanel.columnWeights = new double[] { 1.0, Double.MIN_VALUE };
		gbl_contentPanel.rowWeights = new double[] { 1.0, 0.0, Double.MIN_VALUE };
		contentPanel.setLayout(gbl_contentPanel);

		JLabel lblMessage = new JLabel("<html><div style='text-align: center; padding: 10px;'>"
				+ "Authentication in progress...<br><br>"
				+ "Check your system browser to log in with Zitadel.<br>"
				+ "Complete the authentication on the web page,<br>"
				+ "then return here.</div></html>");
		lblMessage.setHorizontalAlignment(SwingConstants.CENTER);
		lblMessage.setFont(new Font("SansSerif", Font.PLAIN, 13));
		GridBagConstraints gbc_lblMessage = new GridBagConstraints();
		gbc_lblMessage.insets = new Insets(5, 5, 10, 5);
		gbc_lblMessage.fill = GridBagConstraints.BOTH;
		gbc_lblMessage.gridx = 0;
		gbc_lblMessage.gridy = 0;
		contentPanel.add(lblMessage, gbc_lblMessage);

		btnCancel = new JButton("Cancel");
		GridBagConstraints gbc_btnCancel = new GridBagConstraints();
		gbc_btnCancel.anchor = GridBagConstraints.EAST;
		gbc_btnCancel.insets = new Insets(0, 0, 5, 5);
		gbc_btnCancel.gridx = 0;
		gbc_btnCancel.gridy = 1;
		contentPanel.add(btnCancel, gbc_btnCancel);

		btnCancel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				cancelAuthentication();
				m_listener.onLoginClose();
			}
		});

		startAuthentication();
	}

	private void startAuthentication() {
		btnCancel.setEnabled(true);
		setTitle("Authenticating with Zitadel...");

		authWorker = new SwingWorker<Response, Void>() {
			@Override
			protected Response doInBackground() throws Exception {
				sm = new ClientSecurityManager(oauth);
				return sm.authenticateWithPKCE();
			}

			@Override
			protected void done() {
				btnCancel.setEnabled(false);
				try {
					Response ret = get();
					if (ret == null) {
						logger.error("Authentication returned null response");
						setTitle("Authentication failed");
						m_listener.onLoginError(new ErrorResponse(500, "null_response", "No response from authentication"));
						return;
					}

					if (ret.isError()) {
						logger.error(ret);
						setTitle("Authentication failed");
						m_listener.onLoginError((ErrorResponse) ret);
						return;
					}

					if (ret.isJWTResponse()) {
						JWTResponse jwtResp = (JWTResponse) ret;
						String jwt = jwtResp.getAccessToken();
						String userId = extractUserId(jwt);
						logger.info("PKCE authentication successful — user: " + userId);
						setTitle("Authenticated");
						m_listener.onLogin(userId, jwt);
						dispose();
					} else {
						logger.error("Unexpected response type: " + ret.getClass().getName());
						setTitle("Authentication failed");
						m_listener.onLoginError(new ErrorResponse(500, "unexpected_response", "Unexpected response: " + ret));
					}
				} catch (Exception e) {
					logger.error(e.getMessage());
					setTitle("Authentication failed");
					m_listener.onLoginError(new ErrorResponse(500, "auth_exception", e.getMessage()));
				}
			}
		};
		authWorker.execute();
	}

	private void cancelAuthentication() {
		if (authWorker != null && !authWorker.isDone()) {
			authWorker.cancel(true);
		}
	}

	private String extractUserId(String jwt) {
		if (jwt == null)
			return "unknown";
		try {
			SignedJWT signedJWT = SignedJWT.parse(jwt);
			String uid = signedJWT.getJWTClaimsSet().getStringClaim("preferred_username");
			if (uid == null)
				uid = signedJWT.getJWTClaimsSet().getStringClaim("username");
			if (uid == null)
				uid = signedJWT.getJWTClaimsSet().getStringClaim("client_id");
			if (uid == null)
				uid = signedJWT.getJWTClaimsSet().getSubject();
			return uid != null ? uid : "unknown";
		} catch (Exception e) {
			logger.warn("Failed to extract user ID from JWT: " + e.getMessage());
			return "unknown";
		}
	}
}
