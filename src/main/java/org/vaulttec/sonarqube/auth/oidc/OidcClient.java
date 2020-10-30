/*
 * OpenID Connect Authentication for SonarQube
 * Copyright (c) 2017 Torsten Juergeleit
 * mailto:torsten AT vaulttec DOT org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vaulttec.sonarqube.auth.oidc;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.servlet.http.HttpServletRequest;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.openid.connect.sdk.*;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.ResponseType.Value;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenErrorResponse;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.http.ServletUtils;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest.Builder;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.token.OIDCTokens;

@ServerSide
public class OidcClient {

  private static final Logger LOGGER = Loggers.get(OidcClient.class);

  private static final ResponseType RESPONSE_TYPE = new ResponseType(Value.CODE);
  private final OidcConfiguration config;

  public OidcClient(OidcConfiguration config) {
    this.config = config;
  }

  public AuthenticationRequest getAuthenticationRequest(String callbackUrl, String state) {
    AuthenticationRequest request;
    try {
      Builder builder = new AuthenticationRequest.Builder(RESPONSE_TYPE, getScope(), getClientId(),
          new URI(callbackUrl));
      request = builder.endpointURI(getProviderMetadata().getAuthorizationEndpointURI()).state(State.parse(state))
          .build();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Creating new authentication request failed", e);
    }
    LOGGER.debug("Authentication request URI: {}", request.toURI());
    return request;
  }

  public AuthorizationCode getAuthorizationCode(HttpServletRequest callbackRequest) {
    LOGGER.debug("Retrieving authorization code from callback request's query parameters: {}",
        callbackRequest.getQueryString());
    AuthenticationResponse authResponse = null;
    try {
      HTTPRequest request = ServletUtils.createHTTPRequest(callbackRequest);
      authResponse = AuthenticationResponseParser.parse(request.getURL().toURI(), request.getQueryParameters());
    } catch (ParseException | URISyntaxException | IOException e) {
      throw new IllegalStateException("Error while parsing callback request", e);
    }
    if (authResponse instanceof AuthenticationErrorResponse) {
      ErrorObject error = ((AuthenticationErrorResponse) authResponse).getErrorObject();
      throw new IllegalStateException("Authentication request failed: " + error.toJSONObject());
    }
    AuthorizationCode authorizationCode = ((AuthenticationSuccessResponse) authResponse).getAuthorizationCode();
    LOGGER.debug("Authorization code: {}", authorizationCode.getValue());
    return authorizationCode;
  }

  public UserInfo getUserInfo(AuthorizationCode authorizationCode, String callbackUrl) {
    TokenResponse tokenResponse = getTokenResponse(authorizationCode, callbackUrl);
    if (tokenResponse instanceof TokenErrorResponse) {
      ErrorObject errorObject = ((TokenErrorResponse) tokenResponse).getErrorObject();
      if (errorObject == null || errorObject.getCode() == null) {
        throw new IllegalStateException("Token request failed: No error code returned "
            + "(identity provider not reachable - check network proxy setting 'http.nonProxyHosts' in 'sonar.properties')");
      } else {
        throw new IllegalStateException("Token request failed: " + errorObject.toJSONObject());
      }
    }

    OIDCTokens oidcTokens = ((OIDCTokenResponse) tokenResponse).getOIDCTokens();
    UserInfo userInfo;

    try {
      this.validateIDToken(oidcTokens.getIDToken());
    } catch (MalformedURLException e) {
      throw new IllegalStateException("Well-known url incorrect", e);
    } catch (BadJOSEException | JOSEException e) {
      throw new IllegalStateException("ID Token invalid", e);
    }

    try {
      userInfo = new UserInfo(oidcTokens.getIDToken().getJWTClaimsSet());
    } catch (java.text.ParseException e) {
      throw new IllegalStateException("Parsing ID token failed", e);
    }

    if ((userInfo.getName() == null) && (userInfo.getPreferredUsername() == null)) {
      UserInfoResponse userInfoResponse = getUserInfoResponse(oidcTokens.getBearerAccessToken());
      if (userInfoResponse instanceof UserInfoErrorResponse) {
        ErrorObject errorObject = ((UserInfoErrorResponse) userInfoResponse).getErrorObject();
        if (errorObject == null || errorObject.getCode() == null) {
          throw new IllegalStateException("UserInfo request failed: No error code returned "
              + "(identity provider not reachable - check network proxy setting 'http.nonProxyHosts' in 'sonar.properties')");
        } else {
          throw new IllegalStateException("UserInfo request failed: " + errorObject.toJSONObject());
        }
      }
      userInfo = ((UserInfoSuccessResponse) userInfoResponse).getUserInfo();
    }

    LOGGER.debug("User info: {}", userInfo.toJSONObject());
    return userInfo;
  }

  protected TokenResponse getTokenResponse(AuthorizationCode authorizationCode, String callbackUrl) {
    try {
      URI tokenEndpointURI = getProviderMetadata().getTokenEndpointURI();
      LOGGER.debug("Retrieving OIDC tokens with user info claims set from {}", tokenEndpointURI);
      TokenRequest request = new TokenRequest(tokenEndpointURI, new ClientSecretBasic(getClientId(), getClientSecret()),
          new AuthorizationCodeGrant(authorizationCode, new URI(callbackUrl)));
      HTTPResponse response = request.toHTTPRequest().send();
      LOGGER.debug("Token response content: {}", response.getContent());
      return OIDCTokenResponseParser.parse(response);
    } catch (URISyntaxException | ParseException e) {
      throw new IllegalStateException("Retrieving access token failed", e);
    } catch (IOException e) {
      throw new IllegalStateException("Retrieving access token failed: "
          + "Identity provider not reachable - check network proxy setting 'http.nonProxyHosts' in 'sonar.properties'");
    }
  }

  protected UserInfoResponse getUserInfoResponse(BearerAccessToken accessToken) {
    try {
      URI userInfoEndpointURI = getProviderMetadata().getUserInfoEndpointURI();
      LOGGER.debug("Retrieving user info from {}", userInfoEndpointURI);
      UserInfoRequest request = new UserInfoRequest(userInfoEndpointURI, accessToken);
      HTTPResponse response = request.toHTTPRequest().send();
      LOGGER.debug("UserInfo response content: {}", response.getContent());
      return UserInfoResponse.parse(response);
    } catch (ParseException e) {
      throw new IllegalStateException("Retrieving user information failed", e);
    } catch (IOException e) {
      throw new IllegalStateException("Retrieving user information failed: "
          + "Identity provider not reachable - check network proxy setting 'http.nonProxyHosts' in 'sonar.properties'");
    }
  }

  protected OIDCProviderMetadata getProviderMetadata() {
    LOGGER.debug("Retrieving provider metadata from {}", config.issuerUri());
    try {
      return OIDCProviderMetadata.resolve(new Issuer(config.issuerUri()));
    } catch (IOException | GeneralException e) {
      throw new IllegalStateException("Retrieving OpenID Connect provider metadata failed", e);
    }
  }

  private Scope getScope() {
    return Scope.parse(config.scopes());
  }

  private ClientID getClientId() {
    return new ClientID(config.clientId());
  }

  private Secret getClientSecret() {
    String secret = config.clientSecret();
    return secret == null ? new Secret("") : new Secret(secret);
  }

  /**
   * Validate the IdToken as describe in the OIDC spec
   * @param idToken The token to validate
   * @see <a href="</a>https://openid.net/specs/openid-connect-core-1_0.html#IDTokenValidation">OIDC spec</a>
   * @return
   */
  private IDTokenClaimsSet validateIDToken(JWT idToken) throws MalformedURLException, BadJOSEException, JOSEException {
    // The required parameters
    Issuer iss = new Issuer(config.issuerUri());
    ClientID clientID = getClientId();
    JWSAlgorithm jwsAlg = config.JWSAlgorythm();
    IDTokenValidator validator;
    if(jwsAlg.getName().matches("HS")) {
      validator = new IDTokenValidator(iss, clientID, jwsAlg, getClientSecret());
    } else {
      URL jwkSetURL = getProviderMetadata().getJWKSetURI().toURL();
      // Create validator for signed ID tokens
      validator = new IDTokenValidator(iss, clientID, jwsAlg, jwkSetURL);
    }

    Nonce expectedNonce = new Nonce(null);
    return validator.validate(idToken, expectedNonce);
  }

}
