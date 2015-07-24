package org.cloudfoundry.identity.uaa.login;

import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCode;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCodeStore;
import org.cloudfoundry.identity.uaa.error.UaaException;
import org.cloudfoundry.identity.uaa.login.test.ThymeleafConfig;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.exception.InvalidPasswordException;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceAlreadyExistsException;
import org.cloudfoundry.identity.uaa.scim.validate.PasswordValidator;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.UaaUrlUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.spring4.SpringTemplateEngine;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = ThymeleafConfig.class)
public class EmailAccountCreationServiceTests {

    private EmailAccountCreationService emailAccountCreationService;
    private MessageService messageService;
    private ExpiringCodeStore codeStore;
    private ScimUserProvisioning scimUserProvisioning;
    private ClientDetailsService clientDetailsService;
    private ScimUser user = null;
    private ExpiringCode code = null;
    private ClientDetails details = null;
    private PasswordValidator passwordValidator;

    @Autowired
    @Qualifier("mailTemplateEngine")
    SpringTemplateEngine templateEngine;

    @Before
    public void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        messageService = mock(MessageService.class);
        codeStore = mock(ExpiringCodeStore.class);
        scimUserProvisioning = mock(ScimUserProvisioning.class);
        clientDetailsService = mock(ClientDetailsService.class);
        details = mock(ClientDetails.class);
        passwordValidator = mock(PasswordValidator.class);
        emailAccountCreationService = initEmailAccountCreationService("pivotal");
    }

    private EmailAccountCreationService initEmailAccountCreationService(String brand) {
        return new EmailAccountCreationService(templateEngine, messageService, codeStore,
            scimUserProvisioning, clientDetailsService, passwordValidator, new UaaUrlUtils("http://uaa.example.com"),
            brand);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        IdentityZoneHolder.clear();
    }

    @Test
    public void testBeginActivation() throws Exception {
        setUpForSuccess();

        when(scimUserProvisioning.createUser(any(ScimUser.class), anyString())).thenReturn(user);
        when(codeStore.generateCode(anyString(), any(Timestamp.class))).thenReturn(code);
        when(codeStore.retrieveCode(anyString())).thenReturn(code);
        emailAccountCreationService.beginActivation("user@example.com", "password", "login");

        String emailBody = captorEmailBody("Activate your Pivotal ID");

        assertThat(emailBody, containsString("a Pivotal ID"));
        assertThat(emailBody, containsString("<a href=\"http://uaa.example.com/verify_user?code=the_secret_code&amp;email=user%40example.com\">Activate your account</a>"));
        assertThat(emailBody, not(containsString("Cloud Foundry")));
    }

    @Test
    public void testBeginActivationInOtherZone() throws Exception {
        setUpForSuccess();

        IdentityZone zone = MultitenancyFixture.identityZone("test-zone-id", "test");
        IdentityZoneHolder.set(zone);

        when(scimUserProvisioning.createUser(any(ScimUser.class), anyString())).thenReturn(user);
        when(codeStore.generateCode(anyString(), any(Timestamp.class))).thenReturn(code);
        when(codeStore.retrieveCode(anyString())).thenReturn(code);
        emailAccountCreationService.beginActivation("user@example.com", "password", "login");

        String emailBody = captorEmailBody("Activate your account");
        assertThat(emailBody, containsString("A request has been made to activate an account for:"));
        assertThat(emailBody, containsString("<a href=\"http://test.uaa.example.com/verify_user?code=the_secret_code&amp;email=user%40example.com\">Activate your account</a>"));
        assertThat(emailBody, containsString("Thank you,<br />\n    " + zone.getName()));
        assertThat(emailBody, not(containsString("Cloud Foundry")));
        assertThat(emailBody, not(containsString("Pivotal ID")));
    }

    @Test
    public void testBeginActivationWithOssBrand() throws Exception {
        emailAccountCreationService = initEmailAccountCreationService("oss");

        setUpForSuccess();

        when(scimUserProvisioning.createUser(any(ScimUser.class), anyString())).thenReturn(user);
        when(codeStore.generateCode(anyString(), any(Timestamp.class))).thenReturn(code);
        when(codeStore.retrieveCode(anyString())).thenReturn(code);

        emailAccountCreationService.beginActivation("user@example.com", "password", "login");

        String emailBody = captorEmailBody("Activate your account");

        assertThat(emailBody, containsString("an account"));
        assertThat(emailBody, containsString("<a href=\"http://uaa.example.com/verify_user?code=the_secret_code&amp;email=user%40example.com\">Activate your account</a>"));
        assertThat(emailBody, not(containsString("Pivotal")));
    }

    @Test(expected = UaaException.class)
    public void testBeginActivationWithExistingUser() throws Exception {
        setUpForSuccess();
        user.setVerified(true);
        when(scimUserProvisioning.query(anyString())).thenReturn(Arrays.asList(new ScimUser[]{user}));
        when(scimUserProvisioning.createUser(any(ScimUser.class), anyString())).thenThrow(new ScimResourceAlreadyExistsException("duplicate"));
        emailAccountCreationService.beginActivation("user@example.com", "password", "login");
    }

    @Test
    public void testBeginActivationWithUnverifiedExistingUser() throws Exception {
        setUpForSuccess();
        user.setId("existing-user-id");
        user.setVerified(false);
        when(scimUserProvisioning.query(anyString())).thenReturn(Arrays.asList(new ScimUser[]{user}));
        when(scimUserProvisioning.createUser(any(ScimUser.class), anyString())).thenThrow(new ScimResourceAlreadyExistsException("duplicate"));
        when(codeStore.generateCode(anyString(), any(Timestamp.class))).thenReturn(code);
        when(codeStore.retrieveCode(anyString())).thenReturn(code);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setProtocol("http");
        request.setContextPath("/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        emailAccountCreationService.beginActivation("user@example.com", "password", "login");

        verify(messageService).sendMessage(
                eq("user@example.com"),
                eq(MessageType.CREATE_ACCOUNT_CONFIRMATION),
                anyString(),
                anyString()
        );
    }

    @Test
    public void testCompleteActivation() throws Exception {
        setUpForSuccess();
        when(scimUserProvisioning.createUser(any(ScimUser.class), anyString())).thenReturn(user);
        when(codeStore.generateCode(anyString(), any(Timestamp.class))).thenReturn(code);
        when(codeStore.retrieveCode("the_secret_code")).thenReturn(code);
        when(scimUserProvisioning.verifyUser(anyString(), anyInt())).thenReturn(user);
        when(scimUserProvisioning.retrieve(anyString())).thenReturn(user);

        ClientDetails client = mock(ClientDetails.class);
        when(details.getClientId()).thenReturn("login");
        when(details.getAdditionalInformation()).thenReturn(new HashMap<String, Object>());
        when(clientDetailsService.loadClientByClientId(anyString())).thenReturn(client);

        AccountCreationService.AccountCreationResponse accountCreation = emailAccountCreationService.completeActivation("the_secret_code");

        assertEquals("user@example.com", accountCreation.getUsername());
        assertEquals("newly-created-user-id", accountCreation.getUserId());
        assertEquals(null, accountCreation.getRedirectLocation());
        assertNotNull(accountCreation.getUserId());
    }

    @Test
    public void testCompleteActivationWithClientRedirect() throws Exception {
        setUpForSuccess();
        when(scimUserProvisioning.createUser(any(ScimUser.class), anyString())).thenReturn(user);
        when(codeStore.generateCode(anyString(), any(Timestamp.class))).thenReturn(code);
        when(codeStore.retrieveCode("the_secret_code")).thenReturn(code);
        when(scimUserProvisioning.verifyUser(anyString(), anyInt())).thenReturn(user);
        when(scimUserProvisioning.retrieve(anyString())).thenReturn(user);
        when(clientDetailsService.loadClientByClientId(anyString())).thenReturn(details);

        AccountCreationService.AccountCreationResponse accountCreation = emailAccountCreationService.completeActivation("the_secret_code");

        assertEquals("user@example.com", accountCreation.getUsername());
        assertEquals("newly-created-user-id", accountCreation.getUserId());
        assertEquals("http://example.com/redirect", accountCreation.getRedirectLocation());
        assertNotNull(accountCreation.getUserId());
    }

    @Test
    public void testCompleteActivationWithExpiredCode() throws Exception {
        when(codeStore.retrieveCode("expiring_code")).thenReturn(null);
        try {
            emailAccountCreationService.completeActivation("expiring_code");
            fail();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode(), Matchers.equalTo(BAD_REQUEST));
        }
    }

    @Test
    public void testResendVerificationCodeWithPivotalBrand() throws Exception {
        setUpForSuccess();
        setUpResendCodeExpectations();

        emailAccountCreationService.resendVerificationCode(user.getPrimaryEmail(), details.getClientId());

        String emailBody = captorEmailBody("Activate your Pivotal ID");

        assertThat(emailBody, containsString("a Pivotal ID"));
        assertThat(emailBody, containsString("<a href=\"http://uaa.example.com/verify_user?code=the_secret_code&amp;email=user%40example.com\">Activate your account</a>"));
        assertThat(emailBody, containsString("Thank you,<br />\n    Pivotal"));
        assertThat(emailBody, not(containsString("Cloud Foundry")));
    }

    @Test
    public void testResendVerificationCodeWithOssBrand() throws Exception {
        emailAccountCreationService = initEmailAccountCreationService("oss");
        setUpForSuccess();

        setUpResendCodeExpectations();

        emailAccountCreationService.resendVerificationCode(user.getPrimaryEmail(), details.getClientId());

        String emailBody = captorEmailBody("Activate your account");

        assertThat(emailBody, containsString("an account"));
        assertThat(emailBody, containsString("<a href=\"http://uaa.example.com/verify_user?code=the_secret_code&amp;email=user%40example.com\">Activate your account</a>"));
        assertThat(emailBody, containsString("Thank you,<br />\n    Cloud Foundry"));
        assertThat(emailBody, not(containsString("Pivotal")));
    }

    @Test
    public void testResendVerificationCodeWithinZone() throws Exception {
        setUpForSuccess();

        IdentityZone zone = MultitenancyFixture.identityZone("test-zone-id", "test");
        IdentityZoneHolder.set(zone);

        setUpResendCodeExpectations();
        emailAccountCreationService.resendVerificationCode(user.getPrimaryEmail(), details.getClientId());

        String emailBody = captorEmailBody("Activate your account");

        assertThat(emailBody, containsString("an account"));
        assertThat(emailBody, containsString("<a href=\"http://test.uaa.example.com/verify_user?code=the_secret_code&amp;email=user%40example.com\">Activate your account</a>"));
        assertThat(emailBody, not(containsString("Pivotal")));
        assertThat(emailBody, containsString("Thank you,<br />\n    " + zone.getName()));
        assertThat(emailBody, not(containsString("Cloud Foundry")));
    }

    @Test(expected = InvalidPasswordException.class)
    public void beginActivation_throwsException_ifPasswordViolatesPolicy() throws Exception {
        doThrow(new InvalidPasswordException("Oh hell no")).when(passwordValidator).validate(anyString());

        emailAccountCreationService.beginActivation("user@example.com", "some password", null);
        verify(passwordValidator).validate("some password");
    }

    private void setUpForSuccess() throws Exception {
        user = new ScimUser(
                "newly-created-user-id",
                "user@example.com",
                "givenName",
                "familyName");
        user.setPrimaryEmail("user@example.com");
        user.setPassword("password");
        user.setOrigin(Origin.UAA);
        user.setActive(true);
        user.setVerified(false);

        Timestamp ts = new Timestamp(System.currentTimeMillis() + (60 * 60 * 1000)); // 1 hour
        Map<String, Object> data = new HashMap<>();
        data.put("user_id", "newly-created-user-id");
        data.put("client_id", "login");
        code = new ExpiringCode("the_secret_code", ts, JsonUtils.writeValueAsString(data));

        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put(EmailAccountCreationService.SIGNUP_REDIRECT_URL, "http://example.com/redirect");
        when(details.getClientId()).thenReturn("login");
        when(details.getAdditionalInformation()).thenReturn(additionalInfo);
    }

    private void setUpResendCodeExpectations() {
        when(scimUserProvisioning.createUser(any(ScimUser.class), anyString())).thenReturn(user);
        when(codeStore.generateCode(anyString(), any(Timestamp.class))).thenReturn(code);
        when(codeStore.retrieveCode("the_secret_code")).thenReturn(code);
        when(scimUserProvisioning.verifyUser(anyString(), anyInt())).thenReturn(user);
        when(scimUserProvisioning.query(anyString())).thenReturn(Arrays.asList(new ScimUser[]{user}));
        when(scimUserProvisioning.retrieve(anyString())).thenReturn(user);
        when(clientDetailsService.loadClientByClientId(anyString())).thenReturn(details);
    }

    private String captorEmailBody(String subject) {
        ArgumentCaptor<String> emailBodyArgument = ArgumentCaptor.forClass(String.class);
        verify(messageService).sendMessage(
            eq("user@example.com"),
            eq(MessageType.CREATE_ACCOUNT_CONFIRMATION),
            eq(subject),
            emailBodyArgument.capture()
        );
        return emailBodyArgument.getValue();
    }
}
