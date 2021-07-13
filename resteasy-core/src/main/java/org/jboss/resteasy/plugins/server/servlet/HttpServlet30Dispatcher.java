package org.jboss.resteasy.plugins.server.servlet;

import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.specimpl.ResteasyHttpHeaders;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.HttpResponse;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.NewCookie;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
// Note. Setting value to "/RESTEASY_HttpServlet30Dispatcher" frees up the root "/" context
//       to serve static content. The path "/RESTEASY_HttpServlet30Dispatcher" will be installed
//       and it will lead to HttpServlet30Dispatcher, but no resources will be there and status
//       404 will be returned.
@WebServlet(asyncSupported = true, value="/RESTEASY_HttpServlet30Dispatcher")
public class HttpServlet30Dispatcher extends HttpServletDispatcher
{
   /**
    * Setting the corePoolSize to 1 to workaround the
    * <a href="https://bugs.openjdk.java.net/browse/JDK-8129861">bug</a>
    * in openjdk 8, which impacts performance of applications using
    * {@link javax.ws.rs.container.AsyncResponse#setTimeout}.
    */
   private final ScheduledExecutorService asyncCancelScheduler = Executors.newScheduledThreadPool(1);

   @Override
   protected HttpRequest createHttpRequest(String httpMethod, HttpServletRequest httpServletRequest, ResteasyHttpHeaders httpHeaders, ResteasyUriInfo uriInfo, HttpResponse httpResponse, HttpServletResponse httpServletResponse)
   {
      Servlet3AsyncHttpRequest request = new Servlet3AsyncHttpRequest(httpServletRequest, httpServletResponse, getServletContext(), httpResponse, httpHeaders, uriInfo, httpMethod.toUpperCase(), (SynchronousDispatcher) getDispatcher());
      request.asyncScheduler = asyncCancelScheduler;
      return request;
   }

   @Override
   protected HttpResponse createServletResponse(HttpServletResponse response, HttpServletRequest request)
   {
      return new HttpServletResponseWrapper(response, request, getDispatcher().getProviderFactory()) {
         @Override
         public void addNewCookie(NewCookie cookie)
         {
            outputHeaders.add(javax.ws.rs.core.HttpHeaders.SET_COOKIE, cookie);
         }
      };
   }
}
