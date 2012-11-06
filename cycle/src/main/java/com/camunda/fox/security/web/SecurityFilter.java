package com.camunda.fox.security.web;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import com.camunda.fox.cycle.security.IdentityHolder;
import com.camunda.fox.security.UserIdentity;
import com.camunda.fox.security.service.SecurityService;
import static com.camunda.fox.security.web.util.WebUtil.*;

/**
 *
 * @author nico.rehwaldt
 */
public class SecurityFilter implements Filter {

  private WebApplicationContext context;
 
  public static final String IDENTITY_SESSION_KEY = "com.camunda.fox.SecurityFilter.SESSION_KEY";
  public static final String PRE_AUTHENTICATION_URL = "com.camunda.fox.SecurityFilter.LAST_REQUEST_URI";
  
  static final String NOP = "NOP";
  
  @Override
  public void init(FilterConfig config) throws ServletException {
    context = WebApplicationContextUtils.getWebApplicationContext(config.getServletContext());
  }

  @Override
  public void destroy() {
    
  }

  void setWebApplicationContext(WebApplicationContext context) {
    this.context = context;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
    doFilterSecure((HttpServletRequest) request, (HttpServletResponse) response, chain);
  }
  
  void doFilterSecure(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
    UserIdentity identity = getAuthenticatedIdentity(request);
    
    // is the current user authenticated?
    // if yes, make that information available
    if (identity != null) {
      request = wrapAuthenticated(request, identity);
      IdentityHolder.setIdentity(null);
    } 
    // if not, perform security check which may result in a redirect
    else {
      String uri = performSecurityCheck(request.getRequestURI(), request, response);
      if (uri != null) {

        // handle special do nothing actions
        // needed in case of ajax requests where only a 
        // response status is returned
        if (uri.equals(NOP)) {
          return;
        }

        boolean forward = false;

        if (uri.startsWith("forward:")) {
          uri = uri.substring("forward:".length());
          forward = true;
        }

        uri = uri.replace("app:", request.getContextPath() + "/");
        if (forward) {
          request.getRequestDispatcher(uri).forward(request, response);
        } else {
          response.sendRedirect(uri);
        }
        return;
      }
    }
    chain.doFilter(request, response);
  }

  String performSecurityCheck(String requestUri, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    if (requiresAuthentication(requestUri)) {
      if (isAjax(request)) {
        response.sendError(401, "Authorization required");
        return NOP;
      } else {
        if (isGET(request)) {
          request.getSession().setAttribute(PRE_AUTHENTICATION_URL, request.getRequestURI());
        }
        return "forward:/app/login";
      }
    } else
    if (isLoginRequest(request)) {
      if (login(request)) {
        String preLoginUrl = (String) request.getSession().getAttribute(PRE_AUTHENTICATION_URL);
        if (preLoginUrl != null) {
          return preLoginUrl;
        } else {
          return "app:app/secured/view/index";
        }
      } else {
        return "app:app/login/error";
      }
    } else
    if (isLogoutRequest(request)) {
      logout(request);
      return "app:app/login/loggedOut";
    }
    
    return null;
  }
  
  protected UserIdentity getAuthenticatedIdentity(HttpServletRequest request) {
    return (UserIdentity) request.getSession().getAttribute(IDENTITY_SESSION_KEY);
  }

  protected void setAuthenticatedIdentity(HttpServletRequest request, UserIdentity identity) {
    request.getSession().setAttribute(IDENTITY_SESSION_KEY, identity);
  }
  
  protected boolean isLoginRequest(HttpServletRequest request) {
    return requestUriMatches(request, "j_security_check") && isPOST(request);
  }

  private boolean isLogoutRequest(HttpServletRequest request) {
    return requestUriMatches(request, "app/login/logout");
  }

  protected boolean login(HttpServletRequest request) {
    String userName = request.getParameter("j_username");
    String password = request.getParameter("j_password");
    
    if (userName == null || password == null) {
      return false;
    }
    
    SecurityService securityService = context.getBean(SecurityService.class);
    UserIdentity identity = securityService.login(userName, password);
    if (identity != null) {
      setAuthenticatedIdentity(request, identity);
      return true;
    } else {
      return false;
    }
  }

  private void logout(HttpServletRequest request) {
    request.getSession().invalidate();
  }

  private boolean requiresAuthentication(String uri) {
    return uri.matches(".*/app/secured/.*");
  }

  private HttpServletRequest wrapAuthenticated(HttpServletRequest request, UserIdentity identity) {
    return new SecurityWrappedRequest(request, identity);
  }
  
  private boolean requestUriMatches(HttpServletRequest request, String uri) {
    return request.getRequestURI().matches(request.getContextPath() + "/" + uri);
  }
}
