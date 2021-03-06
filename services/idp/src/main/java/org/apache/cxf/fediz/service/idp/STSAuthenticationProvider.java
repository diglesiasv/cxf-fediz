/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.fediz.service.idp;

import java.net.URI;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import javax.xml.namespace.QName;

import org.w3c.dom.Element;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
//import org.apache.cxf.endpoint.Client;
import org.apache.cxf.fediz.core.Claim;
import org.apache.cxf.fediz.core.ClaimTypes;
import org.apache.cxf.fediz.service.idp.kerberos.KerberosServiceRequestToken;
import org.apache.cxf.fediz.service.idp.kerberos.KerberosTokenValidator;
import org.apache.cxf.fediz.service.idp.kerberos.PassThroughKerberosClient;
//import org.apache.cxf.transport.http.HTTPConduit;
//import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.wss4j.common.kerberos.KerberosServiceContext;
import org.apache.wss4j.common.principal.SAMLTokenPrincipalImpl;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.dom.WSConstants;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.opensaml.xml.XMLObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class STSAuthenticationProvider implements AuthenticationProvider {

    private static final String HTTP_DOCS_OASIS_OPEN_ORG_WS_SX_WS_TRUST_200512_BEARER = 
        "http://docs.oasis-open.org/ws-sx/ws-trust/200512/Bearer";
    
    private static final String HTTP_DOCS_OASIS_OPEN_ORG_WS_SX_WS_TRUST_200512 = 
        "http://docs.oasis-open.org/ws-sx/ws-trust/200512/";
    
    private static final String HTTP_SCHEMAS_XMLSOAP_ORG_WS_2005_02_TRUST =
        "http://schemas.xmlsoap.org/ws/2005/02/trust";
    
    private static final Logger LOG = LoggerFactory
            .getLogger(STSAuthenticationProvider.class);

    protected String wsdlLocation;
    
    protected String namespace = HTTP_DOCS_OASIS_OPEN_ORG_WS_SX_WS_TRUST_200512;
    
    protected String wsdlService;

    protected String wsdlEndpoint;

    protected String appliesTo;
    
    protected boolean use200502Namespace;
    
    protected String tokenType;
    
    protected Bus bus;
    
    protected Integer lifetime;
    
    //Required to get IDP roles to use the IDP application, used in future release
    protected String roleURI;
    
    protected Map<String, Object> properties = new HashMap<String, Object>();
    
    private KerberosTokenValidator kerberosTokenValidator;
    
    private CallbackHandler kerberosCallbackHandler;
    
    private boolean kerberosUsernameServiceNameForm;
    
    private boolean requireDelegation;
    
    
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        
        Bus cxfBus = getBus();
        IdpSTSClient sts = new IdpSTSClient(cxfBus);
        sts.setAddressingNamespace("http://www.w3.org/2005/08/addressing");
        if (tokenType != null && tokenType.length() > 0) {
            sts.setTokenType(tokenType);
        } else {
            sts.setTokenType(WSConstants.WSS_SAML2_TOKEN_TYPE);
        }
        sts.setKeyType(HTTP_DOCS_OASIS_OPEN_ORG_WS_SX_WS_TRUST_200512_BEARER);
        sts.setWsdlLocation(wsdlLocation);
        sts.setServiceQName(new QName(namespace, wsdlService));
        sts.setEndpointQName(new QName(namespace, wsdlEndpoint));
        
        Principal kerberosPrincipal = null;
        if (authentication instanceof KerberosServiceRequestToken) {
            // 
            // If delegation is required then validate the received token + store the
            // Delegated Credential so that we can retrieve a new kerberos token for the
            // STS with it. If delegation is not required, then we just get the received
            // token + pass it to the STS
            //
            if (requireDelegation) {
                kerberosPrincipal = 
                    validateKerberosToken((KerberosServiceRequestToken)authentication, sts);
                if (kerberosPrincipal == null) {
                    return null;
                }
            } else {
                PassThroughKerberosClient kerberosClient = new PassThroughKerberosClient();
                kerberosClient.setToken(((KerberosServiceRequestToken)authentication).getToken());
                sts.getProperties().put(SecurityConstants.KERBEROS_CLIENT, kerberosClient);
            }
        } else {
            sts.getProperties().put(SecurityConstants.USERNAME, authentication.getName());
            sts.getProperties().put(SecurityConstants.PASSWORD, (String)authentication.getCredentials());
        }
        
        sts.getProperties().putAll(properties);
        if (use200502Namespace) {
            sts.setNamespace(HTTP_SCHEMAS_XMLSOAP_ORG_WS_2005_02_TRUST);
        }
        
        if (lifetime != null) {
            sts.setEnableLifetime(true);
            sts.setTtl(lifetime.intValue());
        }
        try {

//Line below may be uncommented for debugging    
//            setTimeout(sts.getClient(), 3600000L);

            SecurityToken token = sts.requestSecurityToken(this.appliesTo);
            
            List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
            //authorities.add(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"));
            //Not needed because AuthenticatedVoter has been added for SecurityFlowExecutionListener
            if (roleURI != null) {
                SamlAssertionWrapper assertion = new SamlAssertionWrapper(token.getToken());
                
                List<Claim> claims = parseClaimsInAssertion(assertion.getSaml2());
                for (Claim c : claims) {
                    if (roleURI.equals(c.getClaimType())) {
                        Object oValue = c.getValue();
                        if ((oValue instanceof List<?>) && !((List<?>)oValue).isEmpty()) {
                            List<String> values = (List<String>)oValue;
                            for (String role: values) {
                                authorities.add(new SimpleGrantedAuthority(role));
                            }
                        } else {
                            LOG.error("Unsupported value type of Claim value");
                            throw new IllegalStateException("Unsupported value type of Claim value");
                        }
                        claims.remove(c);
                        break;
                    }
                }
            }
            
            //Add IDP_LOGIN role to be able to access resource Idp, TrustedIdp, etc.
            authorities.add(new SimpleGrantedAuthority("ROLE_IDP_LOGIN"));
            
            if (authentication instanceof KerberosServiceRequestToken) {
                if (kerberosPrincipal == null && token.getToken() != null
                    && "Assertion".equals(token.getToken().getLocalName())) {
                    // For the pass-through Kerberos case, we don't know the Principal name...
                    kerberosPrincipal = 
                        new SAMLTokenPrincipalImpl(new SamlAssertionWrapper(token.getToken()));
                }
                KerberosServiceRequestToken ksrt = 
                    new KerberosServiceRequestToken(kerberosPrincipal, authorities, 
                                                    ((KerberosServiceRequestToken)authentication).getToken());
                
                STSUserDetails details = new STSUserDetails(kerberosPrincipal.getName(),
                                                            "",
                                                            authorities,
                                                            token);
                ksrt.setDetails(details);
                
                LOG.debug("[IDP_TOKEN={}] provided for user '{}'", token.getId(), kerberosPrincipal.getName());
                return ksrt;
            } else {
                UsernamePasswordAuthenticationToken upat = new UsernamePasswordAuthenticationToken(
                    authentication.getName(), authentication.getCredentials(), authorities);
                
                STSUserDetails details = new STSUserDetails(authentication.getName(),
                                                            (String)authentication.getCredentials(),
                                                            authorities,
                                                            token);
                upat.setDetails(details);
                
                LOG.debug("[IDP_TOKEN={}] provided for user '{}'", token.getId(), authentication.getName());
                return upat;
            }
            
        } catch (Exception ex) {
            LOG.info("Failed to authenticate user '" + authentication.getName() + "'", ex);
            return null;
        }
        
    }
    
    private Principal validateKerberosToken(
        KerberosServiceRequestToken token,
        IdpSTSClient sts
    ) {
        if (kerberosTokenValidator == null) {
            LOG.error("KerberosTokenValidator must be configured to support kerberos "
                + "credential delegation");
            return null;
        }
        KerberosServiceContext kerberosContext;
        Principal kerberosPrincipal = null;
        try {
            kerberosContext = kerberosTokenValidator.validate(token);
            if (kerberosContext == null || kerberosContext.getDelegationCredential() == null) {
                LOG.info("Kerberos Validation failure");
                return null;
            }
            GSSCredential delegatedCredential = kerberosContext.getDelegationCredential();
            sts.getProperties().put(SecurityConstants.DELEGATED_CREDENTIAL, 
                                    delegatedCredential);
            sts.getProperties().put(SecurityConstants.KERBEROS_USE_CREDENTIAL_DELEGATION, "true");
            kerberosPrincipal = kerberosContext.getPrincipal();
        } catch (LoginException ex) {
            LOG.info("Failed to authenticate user", ex);
            return null;
        } catch (PrivilegedActionException ex) {
            LOG.info("Failed to authenticate user", ex);
            return null;
        }

        if (kerberosTokenValidator.getContextName() != null) {
            sts.getProperties().put(SecurityConstants.KERBEROS_JAAS_CONTEXT_NAME, 
                                    kerberosTokenValidator.getContextName());
        }
        if (kerberosTokenValidator.getServiceName() != null) {
            sts.getProperties().put(SecurityConstants.KERBEROS_SPN,
                                    kerberosTokenValidator.getServiceName());
        }
        if (kerberosCallbackHandler != null) {
            sts.getProperties().put(SecurityConstants.CALLBACK_HANDLER, 
                                    kerberosCallbackHandler);
        }
        if (kerberosUsernameServiceNameForm) {
            sts.getProperties().put(SecurityConstants.KERBEROS_IS_USERNAME_IN_SERVICENAME_FORM, 
                                    "true");
        }
        
        return kerberosPrincipal;
    }
    
    protected GSSContext createGSSContext() throws GSSException {
        Oid oid = new Oid("1.2.840.113554.1.2.2");

        GSSManager gssManager = GSSManager.getInstance();

        String spn = "bob@service.ws.apache.org";
        GSSName gssService = gssManager.createName(spn, null);

        return gssManager.createContext(gssService.canonicalize(oid),
                                        oid, null, GSSContext.DEFAULT_LIFETIME);

    }


    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class)
            || authentication.equals(KerberosServiceRequestToken.class);
    }
    
    public String getWsdlLocation() {
        return wsdlLocation;
    }

    public void setWsdlLocation(String wsdlLocation) {
        this.wsdlLocation = wsdlLocation;
    }

    public String getWsdlService() {
        return wsdlService;
    }

    public void setWsdlService(String wsdlService) {
        this.wsdlService = wsdlService;
    }

    public String getWsdlEndpoint() {
        return wsdlEndpoint;
    }

    public void setWsdlEndpoint(String wsdlEndpoint) {
        this.wsdlEndpoint = wsdlEndpoint;
    }
    
    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getAppliesTo() {
        return appliesTo;
    }

    public void setAppliesTo(String appliesTo) {
        this.appliesTo = appliesTo;
    }
    
    public void setBus(Bus bus) {
        this.bus = bus;
    }

    public Bus getBus() {
        // do not store a referance to the default bus
        return (bus != null) ? bus : BusFactory.getDefaultBus();
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }
    
    public Integer getLifetime() {
        return lifetime;
    }

    public void setLifetime(Integer lifetime) {
        this.lifetime = lifetime;
    }

    protected List<Claim> parseClaimsInAssertion(org.opensaml.saml2.core.Assertion assertion) {
        List<org.opensaml.saml2.core.AttributeStatement> attributeStatements = assertion
        .getAttributeStatements();
        if (attributeStatements == null || attributeStatements.isEmpty()) {
            LOG.debug("No attribute statements found");
            return Collections.emptyList();
        }

        List<Claim> collection = new ArrayList<Claim>();
        Map<String, Claim> claimsMap = new HashMap<String, Claim>();

        for (org.opensaml.saml2.core.AttributeStatement statement : attributeStatements) {
            LOG.debug("parsing statement: {}", statement.getElementQName());
            List<org.opensaml.saml2.core.Attribute> attributes = statement
            .getAttributes();
            for (org.opensaml.saml2.core.Attribute attribute : attributes) {
                LOG.debug("parsing attribute: {}", attribute.getName());
                Claim c = new Claim();
                // Workaround for CXF-4484 
                // Value of Attribute Name not fully qualified
                // if NameFormat is http://schemas.xmlsoap.org/ws/2005/05/identity/claims
                // but ClaimType value must be fully qualified as Namespace attribute goes away
                URI attrName = URI.create(attribute.getName());
                if (ClaimTypes.URI_BASE.toString().equals(attribute.getNameFormat())
                    && !attrName.isAbsolute()) {
                    c.setClaimType(URI.create(ClaimTypes.URI_BASE + "/" + attribute.getName()));
                } else {
                    c.setClaimType(URI.create(attribute.getName()));
                }
                c.setIssuer(assertion.getIssuer().getNameQualifier());

                List<String> valueList = new ArrayList<String>();
                for (XMLObject attributeValue : attribute.getAttributeValues()) {
                    Element attributeValueElement = attributeValue.getDOM();
                    String value = attributeValueElement.getTextContent();
                    LOG.debug(" [{}]", value);
                    valueList.add(value);
                }
                mergeClaimToMap(claimsMap, c, valueList);
            }
        }
        collection.addAll(claimsMap.values());
        return collection;

    }
    
    protected void mergeClaimToMap(Map<String, Claim> claimsMap, Claim c,
                                   List<String> valueList) {
        Claim t = claimsMap.get(c.getClaimType().toString());
        if (t != null) {
            //same SAML attribute already processed. Thus Claim object already created.
            Object oValue = t.getValue();
            if (oValue instanceof String) {
                //one child element AttributeValue only
                List<String> values = new ArrayList<String>();
                values.add((String)oValue); //add existing value
                values.addAll(valueList);
                t.setValue(values);
            } else if (oValue instanceof List<?>) {
                //more than one child element AttributeValue
                List<String> values = (List<String>)oValue;
                values.addAll(valueList);
                t.setValue(values);
            } else {
                LOG.error("Unsupported value type of Claim value");
                throw new IllegalStateException("Unsupported value type of Claim value");
            }
        } else {
            if (valueList.size() == 1) {
                c.setValue(valueList.get(0));
            } else {
                c.setValue(valueList);
            }
            // Add claim to map
            claimsMap.put(c.getClaimType().toString(), c);
        }
    }

    public String getRoleURI() {
        return roleURI;
    }

    public void setRoleURI(String roleURI) {
        this.roleURI = roleURI;
    }
    
    public void setProperties(Map<String, Object> p) {
        properties.putAll(p);
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public boolean isUse200502Namespace() {
        return use200502Namespace;
    }

    public void setUse200502Namespace(boolean use200502Namespace) {
        this.use200502Namespace = use200502Namespace;
    }

    public KerberosTokenValidator getKerberosTokenValidator() {
        return kerberosTokenValidator;
    }

    public void setKerberosTokenValidator(KerberosTokenValidator kerberosTokenValidator) {
        this.kerberosTokenValidator = kerberosTokenValidator;
    }

    public CallbackHandler getKerberosCallbackHandler() {
        return kerberosCallbackHandler;
    }

    public void setKerberosCallbackHandler(CallbackHandler kerberosCallbackHandler) {
        this.kerberosCallbackHandler = kerberosCallbackHandler;
    }

    public boolean isKerberosUsernameServiceNameForm() {
        return kerberosUsernameServiceNameForm;
    }

    public void setKerberosUsernameServiceNameForm(boolean kerberosUsernameServiceNameForm) {
        this.kerberosUsernameServiceNameForm = kerberosUsernameServiceNameForm;
    }

    public boolean isRequireDelegation() {
        return requireDelegation;
    }

    public void setRequireDelegation(boolean requireDelegation) {
        this.requireDelegation = requireDelegation;
    }

//May be uncommented for debugging    
//    private void setTimeout(Client client, Long timeout) {
//        HTTPConduit conduit = (HTTPConduit) client.getConduit();
//        HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();
//        httpClientPolicy.setConnectionTimeout(timeout);
//        httpClientPolicy.setReceiveTimeout(timeout);
//        conduit.setClient(httpClientPolicy);
//    }
    
}
