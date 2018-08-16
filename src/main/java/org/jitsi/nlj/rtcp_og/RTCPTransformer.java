//package org.jitsi.nlj.rtcp_og;
//
//import org.jitsi.nlj.srtp_og.*;
//import org.jitsi.rtp.rtcp.*;
//
//class RTCPTransformer
//        extends SinglePacketTransformerAdapter
//{
//    /**
//     * Ctor.
//     */
//    RTCPTransformer()
//    {
//        super(RTCPPacketPredicate.INSTANCE);
//    }
//
//    /**
//     * {@inheritDoc}
//     */
//    @Override
//    public RawPacket transform(RawPacket pkt)
//    {
//        // Kill the RRs that FMJ is sending.
//        return doTransform(pkt, true);
//    }
//
//    /**
//     * {@inheritDoc}
//     */
//    @Override
//    public RawPacket reverseTransform(RawPacket pkt)
//    {
//        return doTransform(pkt, false);
//    }
//
//    private RawPacket doTransform(RawPacket pkt, boolean send)
//    {
//        RTCPIterator it = new RTCPIterator(pkt);
//        while (it.hasNext())
//        {
//            ByteArrayBuffer baf = it.next();
//            int pt = RTCPUtils.getPacketType(baf);
//            if (pt == 201 /*RTCPRRPacket.RR*/
//                    || RTCPREMBPacket.isREMBPacket(baf)
//                    || RTCPTCCPacket.isTCCPacket(baf))
//            {
//                it.remove();
//                continue;
//            }
//
//            if (!send && pt > -1)
//            {
//                int fmt = RTCPUtils.getReportCount(baf);
//                if ((pt == RTCPFeedbackMessageEvent.PT_PS
//                        && fmt == RTCPFeedbackMessageEvent.FMT_PLI)
//                        || (pt == RTCPFeedbackMessageEvent.PT_PS
//                        && fmt == RTCPFeedbackMessageEvent.FMT_FIR))
//                {
//                    long source = RTCPFBPacket.getSourceSSRC(baf);
//
////                        if (logger.isTraceEnabled())
////                        {
////                            logger.trace("Relaying a PLI to " + source);
////                        }
//
//                    //TODO(brian): request key frame support
////                        ((RTPTranslatorImpl) stream.getRTPTranslator())
////                                .getRtcpFeedbackMessageSender()
////                                .requestKeyframe(source);
//
//                    it.remove();
//                    continue;
//                }
//            }
//        }
//        return pkt.getLength() == 0 ? null : pkt;
//    }
//}
