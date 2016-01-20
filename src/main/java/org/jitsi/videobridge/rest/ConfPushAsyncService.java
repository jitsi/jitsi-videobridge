package org.jitsi.videobridge.rest;

import org.jitsi.videobridge.Conference;
import org.json.simple.JSONObject;

import javax.servlet.AsyncContext;
import java.io.IOException;

/**
 * Created by brian on 1/19/16.
 */
public class ConfPushAsyncService implements Runnable {
  private final AsyncContext context;
  private final Conference conference;

  public ConfPushAsyncService(AsyncContext context, Conference conference) {
    this.context = context;
    this.conference = conference;
  }

  public void run() {
    try {
      JSONObject pushEvent = conference.getPushEvent();
      pushEvent.writeJSONString(context.getResponse().getWriter());
      context.complete();
    } catch (InterruptedException e) {
      //TODO
    } catch (IOException e) {
      //TODO
    }
  }

}
