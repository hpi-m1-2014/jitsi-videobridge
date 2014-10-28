/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import java.util.*;

import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.sim.*;

/**
 *
 * @author George Politis
 * @author Lyubomir Marinov
 */
@Deprecated
public class MaxThroughputBridgeRTCPTerminationStrategy
    extends AbstractRTCPReportBuilder
    implements BridgeRTCPTerminationStrategy,
               RTCPPacketTransformer
{
    private static final Logger logger
        = Logger.getLogger(MaxThroughputBridgeRTCPTerminationStrategy.class);

    private Conference conference;
    private RTPTranslator rtpTranslator;

    private RTCPSDES createRTCPSDES(RTCPTransmitter rtcpTransmitter, int ssrc)
    {
        SSRCInfo ssrcInfo = rtcpTransmitter.cache.cache.get(ssrc);
        RTCPSDES rtcpSDES = null;

        if (ssrcInfo != null)
        {
            String cname = ssrcInfo.getCNAME();

            if (cname != null)
            {
                rtcpSDES = new RTCPSDES();

                rtcpSDES.ssrc = ssrc;
                rtcpSDES.items
                    = new RTCPSDESItem[]
                            {
                                new RTCPSDESItem(RTCPSDESItem.CNAME, cname)
                            };
            }
        }
        return rtcpSDES;
    }

    @Override
    public RTCPPacketTransformer getRTCPPacketTransformer()
    {
        return this;
    }

    @Override
    public RTCPReportBuilder getRTCPReportBuilder()
    {
        return this;
    }

    private RTCPReportBlock[] makeReceiverReports(
            VideoChannel videoChannel,
            RTCPTransmitter rtcpTransmitter,
            long time)
    {
        SortedSet<SimulcastLayer> layers
            = videoChannel.getSimulcastManager().getSimulcastLayers();
        List<RTCPReportBlock> receiverReports
            = new ArrayList<RTCPReportBlock>(layers.size());

        for (SimulcastLayer layer : layers)
        {
            int ssrc = (int) layer.getPrimarySSRC();
            SSRCInfo info = rtcpTransmitter.cache.cache.get(ssrc);

            if (info != null)
            {
                RTCPReportBlock receiverReport
                        = info.makeReceiverReport(time);

                receiverReports.add(receiverReport);
            }
            else
            {
                // Don't send RTCP feedback information for this sub-stream.
                // TODO(gp) Any endpoints receiving this stream must switch to
                // a lower quality stream.
                if (logger.isInfoEnabled())
                {
                    logger.info(
                            "FMJ has no information for SSRC "
                                + (ssrc & 0xffffffffl) + " (" + ssrc + ")");
                }
            }
        }

        return
            receiverReports.toArray(
                    new RTCPReportBlock[receiverReports.size()]);
    }

    private RTCPREMBPacket makeREMBPacket(
            VideoChannel videoChannel,
            int localSSRC)
    {
        if (videoChannel == null)
            throw new IllegalArgumentException("videoChannel");

        // Media SSRC (always 0)
        final long mediaSSRC = 0l;

        // Destination
        RemoteBitrateEstimator remoteBitrateEstimator
            = ((VideoMediaStream) videoChannel.getStream())
                .getRemoteBitrateEstimator();

        Collection<Integer> tmp = remoteBitrateEstimator.getSsrcs();
        List<Integer> ssrcs = new ArrayList<Integer>(tmp);

        // TODO(gp) intersect with SSRCs from signaled simulcast layers
        long[] dest = new long[ssrcs.size()];
        for (int i = 0; i < ssrcs.size(); i++)
            dest[i] = ssrcs.get(i) & 0xffffffffl;

        // Exp & mantissa
        long bitrate = remoteBitrateEstimator.getLatestEstimate();
        if (bitrate == -1)
        {
            return null;
        }

        if (logger.isDebugEnabled())
            logger.debug("Estimated bitrate: " + bitrate);

        // Create and return the packet.
        return
            new RTCPREMBPacket(
                    localSSRC & 0xFFFFFFFFL,
                    mediaSSRC,
                    bitrate,
                    dest);
    }

    @Override
    public RTCPPacket[] makeReports(RTCPTransmitter rtcpTransmitter)
    {
        RTPTranslator rtpTranslator = this.rtpTranslator;

        if (!(rtpTranslator instanceof RTPTranslatorImpl))
            return null;

        long time = System.currentTimeMillis();

        RTPTranslatorImpl rtpTranslatorImpl = (RTPTranslatorImpl) rtpTranslator;

        // Use the SSRC of the bridge (that is announced through signaling) so
        // that the endpoints won't drop the packet.
        int localSSRC = (int) rtpTranslatorImpl.getLocalSSRC(null);

        for (Endpoint endpoint : this.conference.getEndpoints())
        {
            for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
            {
                // Make the RTCP reports.
                RTCPPacket[] packets
                    = makeReports(
                            (VideoChannel) channel,
                            rtcpTransmitter,
                            time,
                            localSSRC);

                // Transmit the RTCP reports.
                if ((packets != null) && (packets.length != 0))
                {
                    RTCPCompoundPacket compoundPacket
                        = new RTCPCompoundPacket(packets);
                    Payload payload = new RTCPPacketPayload(compoundPacket);

                    rtpTranslatorImpl.writeControlPayload(
                            payload,
                            channel.getStream());

                    /*
                     * NOTE(gp, lyubomir): RTCPTransmitter cannot transmit
                     * specific reports to specific destinations so we've
                     * implemented the transmission ourselves. We're updating
                     * the (global) transmission statistics maintained by
                     * RTCPTranmitter by calling its onRTCPCompoundPacketSent
                     * method.
                     */
                    rtcpTransmitter.onRTCPCompoundPacketSent(compoundPacket);
                }
            }
        }

        return null;
    }

    private RTCPPacket[] makeReports(
            VideoChannel videoChannel,
            RTCPTransmitter rtcpTransmitter,
            long time,
            int localSSRC)
    {
        // RTCP RR
        RTCPReportBlock[] receiverReports
            = makeReceiverReports(videoChannel, rtcpTransmitter, time);
        RTCPPacket rr = new RTCPRRPacket(localSSRC, receiverReports);

        // RTCP REMB
        RTCPREMBPacket remb = makeREMBPacket(videoChannel, localSSRC);

        if (remb != null)
        {
            if (logger.isDebugEnabled())
                logger.debug(remb);
        }

        // RTCP SDES
        List<RTCPSDES> sdesChunks
            = new ArrayList<RTCPSDES>(1 + receiverReports.length);
        RTCPSDES sdesChunk = createRTCPSDES(rtcpTransmitter, localSSRC);

        if (sdesChunk != null)
            sdesChunks.add(sdesChunk);

        long[] dest = new long[receiverReports.length];

        for (int i = 0; i < dest.length; i++)
            dest[i] = receiverReports[i].getSSRC();

        for (long ssrc : dest)
        {
            sdesChunk = createRTCPSDES(rtcpTransmitter, (int) ssrc);
            if (sdesChunk != null)
                sdesChunks.add(sdesChunk);
        }

        RTCPSDESPacket sdes
            = new RTCPSDESPacket(
                    sdesChunks.toArray(new RTCPSDES[sdesChunks.size()]));

        return (remb != null)
                ? new RTCPPacket[] { rr, remb, sdes }
                : new RTCPPacket[] { rr, sdes };
    }

    @Override
    public void setConference(Conference conference)
    {
        this.conference = conference;
    }

    @Override
    public Conference getConference()
    {
        return this.conference;
    }

    @Override
    public void setRTPTranslator(RTPTranslator translator)
    {
        this.rtpTranslator = translator;
    }

    @Override
    public RTPTranslator getRTPTranslator()
    {
        return this.rtpTranslator;
    }

    @Override
    public RTCPCompoundPacket transformRTCPPacket(RTCPCompoundPacket inPacket)
    {
        if (inPacket == null)
            return inPacket;

        RTCPPacket[] inPackets = inPacket.packets;

        if ((inPackets == null) || (inPackets.length == 0))
            return inPacket;

        List<RTCPPacket> outPackets
            = new ArrayList<RTCPPacket>(inPackets.length);

        for (RTCPPacket p : inPackets)
        {
            switch (p.type)
            {
            case RTCPPacket.RR:
                // Mute RRs from the peers. We send our own.
                break;

            case RTCPPacket.SR:
                // Remove feedback information from the SR and forward.
                RTCPSRPacket sr = (RTCPSRPacket) p;

                sr.reports = new RTCPReportBlock[0];
                outPackets.add(sr);
                break;

            case RTCPFBPacket.PSFB:
                RTCPFBPacket psfb = (RTCPFBPacket) p;

                switch (psfb.fmt)
                {
                case RTCPREMBPacket.FMT:
                    // Mute REMBs.
                    break;
                default:
                    // Pass through everything else, like PLIs and NACKs
                    outPackets.add(psfb);
                    break;
                }
                break;

            default:
                // Pass through everything else, like PLIs and NACKs
                outPackets.add(p);
                break;
            }
        }

        RTCPCompoundPacket outPacket;

        if (outPackets.isEmpty())
        {
            outPacket = null;
        }
        else
        {
            outPacket
                = new RTCPCompoundPacket(
                        outPackets.toArray(new RTCPPacket[outPackets.size()]));
        }
        return outPacket;
    }
}
