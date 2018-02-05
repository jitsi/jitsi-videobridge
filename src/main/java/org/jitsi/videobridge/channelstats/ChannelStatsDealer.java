package org.jitsi.videobridge.channelstats;

import net.java.sip.communicator.util.Logger;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.eventadmin.Event;
import org.jitsi.osgi.EventHandlerActivator;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.videobridge.Channel;
import org.jitsi.videobridge.EventFactory;
import org.osgi.framework.BundleContext;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

public class ChannelStatsDealer
        extends EventHandlerActivator {

    private final static String CFG_CHANNEL_STATS_BASE
            = "org.jitsi.videobridge.ChannelStatsDealer";

    /**
     * The name of the configuration property which configures
     * {@link #isOn}.
     */
    public final static String CFG_CHANNEL_STATS_ON
            = CFG_CHANNEL_STATS_BASE + ".ON";

    /**
     * The name of the configuration property which configures
     * {@link #endpointOrigin}.
     */
    public static final String CFG_CHANNEL_STATS_ORIGIN
            = CFG_CHANNEL_STATS_BASE + ".ORIGIN";

    /**
     * The name of the configuration property which configures
     * {@link #endpointOrigin}.
     */
    public static final String CFG_CHANNEL_STATS_ACCEPT_SELF_SIGNED
            = CFG_CHANNEL_STATS_BASE + ".ACCEPT_SELF_SIGNED";


    /**
     * OSGi BC for this module.
     */
    private BundleContext bundleContext;

    /**
     * Is Channel stats dealer on (if tru will attempt to send data to the
     * selected origin each time receives the event
     */
    private boolean isOn;

    /**
     * Endpoint origin (by default "http://127.0.0.1:9100/channelUpdates/"),
     * origin were we plan to send admin events.
     */
    private String endpointOrigin;

    /**
     * Will accept self signed certificates for https origin endpoint
     */
    private boolean acceptSelfSigned;

    /**
     * The default value for {@link #isOn}.
     */
    private final static boolean DEFAULT_IS_ON = false;

    /**
     * The default value for {@link #endpointOrigin}.
     */
    private final static String DEFAULT_ENDPOINT_ORIGIN =
            "http://127.0.0.1:9100/channelUpdates/";

    /**
     * Default value for {@link #acceptSelfSigned}
     */
    private final static boolean DEFAULT_ACCEPT_SELF_SIGNED = false;

    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
            = Logger.getLogger(ChannelStatsDealer.class);

    public ChannelStatsDealer()
    {
        super(new String[] { EventFactory.CHANNEL_CREATED_TOPIC, EventFactory.CHANNEL_EXPIRED_TOPIC });
    }

    @Override
    public void start(BundleContext bundleContext)
            throws Exception
    {
        this.bundleContext = bundleContext;

        ConfigurationService config = ServiceUtils.getService(
                bundleContext, ConfigurationService.class);

        isOn = config.getBoolean(
                CFG_CHANNEL_STATS_ON,
                DEFAULT_IS_ON);

        endpointOrigin = config.getString(
                CFG_CHANNEL_STATS_ORIGIN,
                DEFAULT_ENDPOINT_ORIGIN);

        acceptSelfSigned = config.getBoolean(
                CFG_CHANNEL_STATS_ACCEPT_SELF_SIGNED,
                DEFAULT_ACCEPT_SELF_SIGNED);

        super.start(bundleContext);
    }

    @Override
    public void stop(BundleContext bundleContext)
            throws Exception
    {
        super.stop(bundleContext);
        this.bundleContext = null;
    }


    @Override
    public void handleEvent(Event event) {

        if (event == null)
        {
            logger.debug("Could not handle an event because it was null.");
            return;
        }

        String topic = event.getTopic();

        if (topic.equals(EventFactory.CHANNEL_CREATED_TOPIC)) {
            channelEvent("channelCreated", event);
        } else if (topic.equals(EventFactory.CHANNEL_EXPIRED_TOPIC)) {
            channelEvent("channelExpired", event);
        }
    }

    private void channelEvent(String type, Event event) {
        final Object eventSource = event.getProperty(EventFactory.EVENT_SOURCE);
        if (eventSource instanceof Channel) {
            final Channel channel = (Channel)eventSource;
            final String channelID = channel.getID();
            sendRequest(type, channelID);
        } else {
            logger.error("bad event source type for " + type + ": " +
                    eventSource.getClass().getSimpleName());
        }
    }

    private TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
                public void checkClientTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
                public void checkServerTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
            }
    };

    private void sendRequest(String type, String channelID) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpointOrigin + type + "/" + channelID);
            connection =
                    (HttpURLConnection) url.openConnection();

            if (acceptSelfSigned) {
                SSLContext sc = SSLContext.getInstance("TLSv1.2");
                sc.init(null, trustAllCerts,
                        new java.security.SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(
                        sc.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier(
                        new HostnameVerifier() {
                    @Override
                    public boolean verify(String arg0, SSLSession arg1) {
                        return true;
                    }
                });
            }

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type",
                    "application/text");
            connection.setRequestProperty("Content-Length",
                    Integer.toString(channelID.getBytes().length));
            connection.setRequestProperty("Content-Language", "en-US");
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream (
                    connection.getOutputStream());
            wr.writeBytes(channelID);
            wr.close();
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            rd.close();
        } catch (java.io.IOException e) {
            logger.error("malformed url: ", e);
        } catch (NoSuchAlgorithmException e) {
            logger.error("no such algorithm: ", e);
        } catch (KeyManagementException e) {
            logger.error("key manager exception: ", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
