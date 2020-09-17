/*
 * Copyright @ 2015 - Present, 8x8 Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.cc;

import org.jitsi.nlj.rtp.bandwidthestimation.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.cc.config.*;
import org.json.simple.*;

import java.util.*;

/**
  * @author George Politis
  */
 public class BandwidthProbing
     extends PeriodicRunnable implements BandwidthEstimator.Listener
 {
     /**
      * The {@link TimeSeriesLogger} to be used by this instance to print time
      * series.
      */
     private static final TimeSeriesLogger timeSeriesLogger
         = TimeSeriesLogger.getTimeSeriesLogger(BandwidthProbing.class);

     private static Random random = new Random();

     /**
      * The sequence number to use if probing with the JVB's SSRC.
      */
     private int seqNum = random.nextInt(0xFFFF);

     /**
      * The RTP timestamp to use if probing with the JVB's SSRC.
      */
     private long ts = random.nextInt() & 0xFFFFFFFFL;

     /**
      * Whether or not probing is currently enabled
      */
     public boolean enabled = false;

     /**
      * The number of bytes left over from one run of probing to the next.  This
      * avoids accumulated rounding errors causing us to under-shoot the probing
      * bandwidth, and also handles the use when the number of bytes we want to
      * send is less than the size of an RTP header.
      */
     double bytesLeftOver = 0;

     private Long latestBwe = -1L;

     private DiagnosticContext diagnosticContext;

     private BitrateController bitrateController;

     private ProbingDataSender probingDataSender;

     private static final BandwidthProbingConfig config = new BandwidthProbingConfig();

     /**
      * Ctor.
      *
      */
     public BandwidthProbing(ProbingDataSender probingDataSender)
     {
         super(config.getPaddingPeriodMs());
         this.probingDataSender = probingDataSender;
     }

     /**
      * Sets the diagnostic context.
      */
     public void setDiagnosticContext(DiagnosticContext diagnosticContext)
     {
         this.diagnosticContext = diagnosticContext;
     }

     /**
      * TODO(brian): there's data we need from bitratecontroller that may be
      * tough to get another way. for now, i've tried to at least minimize the
      * dependency by creating the #getStatusSnapshot method inside
      * bitratecontroller that this can use (so it doesn't have to depend on
      * accessing the source descriptions)
      */
     public void setBitrateController(BitrateController bitrateController)
     {
        this.bitrateController = bitrateController;
     }

     /**
      * {@inheritDoc}
      */
     @Override
     public void run()
     {
         super.run();

         if (!enabled)
         {
             return;
         }

         // We calculate how much to probe for based on the total target bps
         // (what we're able to reach), the total ideal bps (what we want to
         // be able to reach) and the total current bps (what we currently send).
         BitrateController.StatusSnapshot bitrateControllerStatus = bitrateController.getStatusSnapshot();

         // How much padding do we need?
         long totalNeededBps = bitrateControllerStatus.currentIdealBps - bitrateControllerStatus.currentTargetBps;
         if (totalNeededBps < 1)
         {
             // Don't need to send any probing.
             bytesLeftOver = 0;
             return;
         }

         long latestBweCopy = latestBwe;

         if (bitrateControllerStatus.currentIdealBps <= latestBweCopy)
         {
             // it seems like the ideal bps fits in the bandwidth estimation,
             // let's update the bitrate controller.
             //TODO(brian): this trigger for a bitratecontroller update seems awkward and may not be obsolete
             // since i now update it every time we get an updated estimate from bandwidth estimator
//             dest.getBitrateController().update(bweBps);
             return;
         }

         // How much padding can we afford?
         long maxPaddingBps = latestBweCopy - bitrateControllerStatus.currentTargetBps;
         long paddingBps = Math.min(totalNeededBps, maxPaddingBps);

         DiagnosticContext.TimeSeriesPoint timeSeriesPoint = null;

         double newBytesNeeded = (config.getPaddingPeriodMs() * paddingBps / 1000.0 / 8.0);
         double bytesNeeded = newBytesNeeded + bytesLeftOver;

         if (timeSeriesLogger.isTraceEnabled() && diagnosticContext != null)
         {
             timeSeriesPoint = diagnosticContext
                     .makeTimeSeriesPoint("sent_padding")
                     .addField("padding_bps", paddingBps)
                     .addField("total_ideal_bps", bitrateControllerStatus.currentIdealBps)
                     .addField("total_target_bps", bitrateControllerStatus.currentTargetBps)
                     .addField("needed_bps", totalNeededBps)
                     .addField("max_padding_bps", maxPaddingBps)
                     .addField("bwe_bps", latestBweCopy)
                     .addField("bytes_needed", bytesNeeded)
                     .addField("prev_bytes_left_over", bytesLeftOver);
         }

         if (bytesNeeded >= 1)
         {
             int bytesSent = probingDataSender.sendProbing(bitrateControllerStatus.activeSsrcs, (int)bytesNeeded);

             bytesLeftOver = Math.max(bytesNeeded - bytesSent, 0);

             if (timeSeriesPoint != null)
             {
                 timeSeriesPoint.addField("bytes_sent", bytesSent)
                    .addField("new_bytes_left_over", bytesLeftOver);
             }
         }
         else
         {
             bytesLeftOver = Math.max(bytesNeeded, 0);
         }

         if (timeSeriesLogger.isTraceEnabled() && timeSeriesPoint != null)
         {
             timeSeriesLogger.trace(timeSeriesPoint);
         }
     }

     @Override
     public void bandwidthEstimationChanged(Bandwidth newBw)
     {
         this.latestBwe = (long)newBw.getBps();
     }

     /**
      * Gets a JSON representation of the parts of this object's state that
      * are deemed useful for debugging.
      */
     @SuppressWarnings("unchecked")
     public JSONObject getDebugState()
     {
         JSONObject debugState = new JSONObject();
         debugState.put("seqNum", seqNum);
         debugState.put("ts", ts);
         debugState.put("enabled", enabled);
         debugState.put("latestBwe", latestBwe);

         return debugState;
     }

     public interface ProbingDataSender
     {
         /**
          * Sends a specific number of bytes with a specific set of SSRCs.
          * @param mediaSsrcs the SSRCs
          * @param numBytes the number of probing bytes we want to send
          * @return the number of bytes of probing data actually sent
          */
         int sendProbing(Collection<Long> mediaSsrcs, int numBytes);
     }
 }
