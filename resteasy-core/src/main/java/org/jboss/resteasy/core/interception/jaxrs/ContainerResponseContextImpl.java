package org.jboss.resteasy.core.interception.jaxrs;

import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.core.ServerResponseWriter.RunnableWithIOException;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.core.ResteasyContext.CloseableContext;
import org.jboss.resteasy.resteasy_jaxrs.i18n.LogMessages;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.jboss.resteasy.spi.ApplicationException;
import org.jboss.resteasy.spi.Dispatcher;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.ResteasyAsynchronousResponse;
import org.jboss.resteasy.tracing.RESTEasyTracingLogger;

import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class ContainerResponseContextImpl implements SuspendableContainerResponseContext
{
   protected final HttpRequest request;
   protected final HttpResponse httpResponse;
   protected final BuiltResponse jaxrsResponse;
   private ResponseContainerRequestContext requestContext;
   private ContainerResponseFilter[] responseFilters;
   private RunnableWithIOException continuation;
   private int currentFilter;
   private boolean suspended;
   private boolean filterReturnIsMeaningful = true;
   private Map<Class<?>, Object> contextDataMap;
   private boolean inFilter;
   private Throwable throwable;
   private Consumer<Throwable> onComplete;
   private boolean weSuspended;

   @Deprecated
   public ContainerResponseContextImpl(final HttpRequest request, final HttpResponse httpResponse, final BuiltResponse serverResponse)
   {
      this(request, httpResponse, serverResponse, null, new ContainerResponseFilter[]{}, t -> {}, null);
   }

   public ContainerResponseContextImpl(final HttpRequest request, final HttpResponse httpResponse, final BuiltResponse serverResponse,
                                       final ResponseContainerRequestContext requestContext, final ContainerResponseFilter[] responseFilters,
                                       final Consumer<Throwable> onComplete, final RunnableWithIOException continuation)
   {
      this.request = request;
      this.httpResponse = httpResponse;
      this.jaxrsResponse = serverResponse;
      this.requestContext = requestContext;
      this.responseFilters = responseFilters;
      this.continuation = continuation;
      this.onComplete = onComplete;
      contextDataMap = ResteasyContext.getContextDataMap();
   }

   public BuiltResponse getJaxrsResponse()
   {
      return jaxrsResponse;
   }

   public HttpResponse getHttpResponse()
   {
      return httpResponse;
   }

   @Override
   public int getStatus()
   {
      return jaxrsResponse.getStatus();
   }

   @Override
   public void setStatus(int code)
   {
      httpResponse.setStatus(code);
      jaxrsResponse.setStatus(code);
   }

   @Override
   public Response.StatusType getStatusInfo()
   {
      return jaxrsResponse.getStatusInfo();
   }

   @Override
   public void setStatusInfo(Response.StatusType statusInfo)
   {
      httpResponse.setStatus(statusInfo.getStatusCode());
      jaxrsResponse.setStatus(statusInfo.getStatusCode());
   }

   @Override
   public Class<?> getEntityClass()
   {
      return jaxrsResponse.getEntityClass();
   }

   @Override
   public Type getEntityType()
   {
      return jaxrsResponse.getGenericType();
   }

   @Override
   public void setEntity(Object entity)
   {
      //if (entity != null) logger.info("*** setEntity(Object) " + entity.toString());
      jaxrsResponse.setEntity(entity);
      // todo TCK does weird things in its testing of get length
      // it resets the entity in a response filter which results
      // in a bad content-length being sent back to the client
      // so, we'll remove any content-length setting
      getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
   }

   @Override
   public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType)
   {
      //if (entity != null) logger.info("*** setEntity(Object, Annotation[], MediaType) " + entity.toString() + ", " + mediaType);
      jaxrsResponse.setEntity(entity);
      jaxrsResponse.setAnnotations(annotations);
      jaxrsResponse.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, mediaType);
      // todo TCK does weird things in its testing of get length
      // it resets the entity in a response filter which results
      // in a bad content-length being sent back to the client
      // so, we'll remove any content-length setting
      getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
   }

   @Override
   public MultivaluedMap<String, Object> getHeaders()
   {
      return jaxrsResponse.getMetadata();
   }

   @Override
   public Set<String> getAllowedMethods()
   {
      return jaxrsResponse.getAllowedMethods();
   }

   @Override
   public Date getDate()
   {
      return jaxrsResponse.getDate();
   }

   @Override
   public Locale getLanguage()
   {
      return jaxrsResponse.getLanguage();
   }

   @Override
   public int getLength()
   {
      return jaxrsResponse.getLength();
   }

   @Override
   public MediaType getMediaType()
   {
      return jaxrsResponse.getMediaType();
   }

   @Override
   public Map<String, NewCookie> getCookies()
   {
      return jaxrsResponse.getCookies();
   }

   @Override
   public EntityTag getEntityTag()
   {
      return jaxrsResponse.getEntityTag();
   }

   @Override
   public Date getLastModified()
   {
      return jaxrsResponse.getLastModified();
   }

   @Override
   public URI getLocation()
   {
      return jaxrsResponse.getLocation();
   }

   @Override
   public Set<Link> getLinks()
   {
      return jaxrsResponse.getLinks();
   }

   @Override
   public boolean hasLink(String relation)
   {
      return jaxrsResponse.hasLink(relation);
   }

   @Override
   public Link getLink(String relation)
   {
      return jaxrsResponse.getLink(relation);
   }

   @Override
   public Link.Builder getLinkBuilder(String relation)
   {
      return jaxrsResponse.getLinkBuilder(relation);
   }

   @Override
   public boolean hasEntity()
   {
      return !jaxrsResponse.isClosed() && jaxrsResponse.hasEntity();
   }

   @Override
   public Object getEntity()
   {
      return !jaxrsResponse.isClosed() ? jaxrsResponse.getEntity() : null;
   }

   @Override
   public OutputStream getEntityStream()
   {
      try
      {
         return httpResponse.getOutputStream();
      }
      catch (IOException e)
      {
         throw new RuntimeException(e);
      }
   }

   @Override
   public void setEntityStream(OutputStream entityStream)
   {
      httpResponse.setOutputStream(entityStream);
   }

   @Override
   public Annotation[] getEntityAnnotations()
   {
      return jaxrsResponse.getAnnotations();
   }

   @Override
   public MultivaluedMap<String, String> getStringHeaders()
   {
      return jaxrsResponse.getStringHeaders();
   }

   @Override
   public String getHeaderString(String name)
   {
      return jaxrsResponse.getHeaderString(name);
   }


   @Override
   public synchronized void suspend() {
      if(continuation == null)
         throw new RuntimeException("Suspend not supported yet");
      suspended = true;
   }

   @Override
   public synchronized void resume() {
      if(!suspended)
         throw new RuntimeException("Cannot resume: not suspended");
      if(inFilter)
      {
         // suspend/resume within filter, same thread: just ignore and move on
         suspended = false;
         return;
      }

      // go on, but with proper exception handling
      try(CloseableContext c = ResteasyContext.addCloseableContextDataLevel(contextDataMap)){
         filter();
      }catch(Throwable t) {
         // don't throw to client
         writeException(t);
      }
   }

   @Override
   public synchronized void resume(Throwable t) {
      if(!suspended)
         throw new RuntimeException("Cannot resume: not suspended");
      if(inFilter)
      {
         // not suspended, or suspend/abortWith within filter, same thread: collect and move on
         throwable = t;
         suspended = false;
      }
      else
      {
         try(CloseableContext c = ResteasyContext.addCloseableContextDataLevel(contextDataMap)){
            writeException(t);
         }
      }
   }

   private void writeException(Throwable t)
   {
      /*
       * Here we cannot call AsyncResponse.resume(t) because that would invoke the response filters
       * and we should not invoke them because we're already in them.
       */
      HttpResponse httpResponse = (HttpResponse) contextDataMap.get(HttpResponse.class);
      SynchronousDispatcher dispatcher = (SynchronousDispatcher) contextDataMap.get(Dispatcher.class);
      ResteasyAsynchronousResponse asyncResponse = request.getAsyncContext().getAsyncResponse();

      RESTEasyTracingLogger tracingLogger = RESTEasyTracingLogger.getInstance(request);
      tracingLogger.flush(httpResponse.getOutputHeaders());

      dispatcher.unhandledAsynchronousException(httpResponse, t);
      onComplete.accept(t);
      asyncResponse.complete();
      asyncResponse.completionCallbacks(t);
   }

   public synchronized void filter() throws IOException
   {
      RESTEasyTracingLogger logger = RESTEasyTracingLogger.getInstance(request);

      while(currentFilter < responseFilters.length)
      {
         ContainerResponseFilter filter = responseFilters[currentFilter++];
         try
         {
            suspended = false;
            throwable = null;
            inFilter = true;
            final long timestamp = logger.timestamp("RESPONSE_FILTER");
            filter.filter(requestContext, this);
            logger.logDuration("RESPONSE_FILTER", timestamp, filter);
         }
         catch (IOException e)
         {
            throw new ApplicationException(e);
         }
         finally
         {
            inFilter = false;
         }
         if(suspended) {
            if(!request.getAsyncContext().isSuspended())
            {
               request.getAsyncContext().suspend();
               weSuspended = true;
            }
            // ignore any abort request until we are resumed
            filterReturnIsMeaningful = false;
            return;
         }
         if (throwable != null)
         {
            // handle the case where we've been suspended by a previous filter
            if(filterReturnIsMeaningful)
               SynchronousDispatcher.rethrow(throwable);
            else
            {
               writeException(throwable);
               return;
            }
         }
      }
      // here it means we reached the last filter

      // some frameworks don't support async request filters, in which case suspend() is forbidden
      // so if we get here we're still synchronous and don't have a continuation, which must be in
      // the caller
      if(continuation == null)
         return;

      // if we've never been suspended, the caller is valid so let it handle any exception
      if(filterReturnIsMeaningful) {
         continuation.run(onComplete);
         return;
      }
      // if we've been suspended then the caller is a filter and have to invoke our continuation
      // FIXME: we don't really know if we're already trying to send an exception, so we can't just blindly
      // try to write it out
      try
      {
         continuation.run((t) -> {
            onComplete.accept(t);
            if(weSuspended)
            {
               // if we're the ones who turned the request async, nobody will call complete() for us, so we have to
               request.getAsyncContext().complete();
            }
         });
      } catch (IOException e)
      {
         LogMessages.LOGGER.unknownException(request.getHttpMethod(), request.getUri().getPath(), e);
      }
   }
}
