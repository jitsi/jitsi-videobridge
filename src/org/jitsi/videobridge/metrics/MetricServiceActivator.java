package org.jitsi.videobridge.metrics;

import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.videobridge.osgi.ServiceUtils2;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * OSGi activator for the <tt>MetricService</tt>
 *
 * @author zbettenbuk
 */
public class MetricServiceActivator implements BundleActivator {

  private MetricService metricService;

  private ServiceRegistration<MetricService> serviceRegistration;

  @Override
  public void start(BundleContext bundleContext) throws Exception {
    ConfigurationService config = ServiceUtils2.getService(bundleContext, ConfigurationService.class);
    this.metricService = new MetricService(config);
    this.serviceRegistration = bundleContext.registerService(MetricService.class, this.metricService, null);
  }

  @Override
  public void stop(BundleContext bundleContext) throws Exception {
    if (this.serviceRegistration != null) {
      this.serviceRegistration.unregister();
    }
  }

}
