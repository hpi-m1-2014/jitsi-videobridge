package de.hpi.uni_potsdam.fb10_mp1.recording;

import org.slf4j.Logger;
import org.ifsoft.rtp.Vp8Accumulator;
import org.ifsoft.rtp.Vp8Packet;
import org.jitsi.impl.neomedia.MediaStreamImpl;
import org.jitsi.impl.neomedia.RTPConnectorInputStream;
import org.jitsi.impl.neomedia.RawPacket;
import org.jitsi.service.neomedia.MediaStream;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.Arrays;

public class Participant
{
    private static final Logger Log = LoggerFactory.getLogger(Participant.class);

    /**
     *
     *
     */
    private Integer lastSequenceNumber;
    /**
     *
     *
     */
    private Boolean sequenceNumberingViolated;
    /**
     *
     *
     */
    private String focusName;
    /**
     *
     *
     */
    private Recorder recorder = null;
    /**
     *
     *
     */
    public MediaStream videoStream = null;
    /**
     *
     *
     */
    public MediaStream audioStream = null;
    /**
     *
     *
     */
    public String audioChannelId;
    /**
     *
     *
     */
    public String videoChannelId;
    /**
     *
     *
     */
    private String nickname;
    /**
     *
     *
     */

    private Vp8Accumulator _accumulator = new Vp8Accumulator();
    private Integer _lastSequenceNumber =  Integer.valueOf(-1);
    private boolean _sequenceNumberingViolated = false;

    private Participant me = this;
    private int snapshot = 0;
    private JID user;

    /**
     *
     *
     */
    private PropertyChangeListener audioStreamPropertyChangeListener = new PropertyChangeListener()
    {
        public void propertyChange(PropertyChangeEvent ev)
        {
            String propertyName = ev.getPropertyName();
            String prefix = MediaStreamImpl.class.getName() + ".rtpConnector.";

            if (propertyName.startsWith(prefix))
            {
                Object newValue = ev.getNewValue();

                if (newValue instanceof RTPConnectorInputStream)
                {
                    String rtpConnectorPropertyName = propertyName.substring(prefix.length());

                    if (rtpConnectorPropertyName.equals("dataInputStream"))
                    {
                        Log.info("PropertyChangeListener " + rtpConnectorPropertyName);

                        ((RTPConnectorInputStream) newValue).audioScanner = me;
                    }
                }
            }
        }
    };

    private PropertyChangeListener videoStreamPropertyChangeListener = new PropertyChangeListener()
    {
        public void propertyChange(PropertyChangeEvent ev)
        {
            String propertyName = ev.getPropertyName();
            String prefix = MediaStreamImpl.class.getName() + ".rtpConnector.";

            if (propertyName.startsWith(prefix))
            {
                Object newValue = ev.getNewValue();

                if (newValue instanceof RTPConnectorInputStream)
                {
                    String rtpConnectorPropertyName = propertyName.substring(prefix.length());

                    if (rtpConnectorPropertyName.equals("dataInputStream"))
                    {
                        Log.info("PropertyChangeListener " + rtpConnectorPropertyName);

                        ((RTPConnectorInputStream) newValue).videoRecorder = me;
                    }
                }
            }
        }
    };
    /**
     *
     *
     */
    synchronized public void scanData(final RawPacket packet)
    {
        //if (snapshot < 10) Log.info("scanData " + packet.getPayloadLength() + " " + packet.getHeaderLength()  + " " + packet.getExtensionLength());

        if (packet != null)
        {
            final byte[] rtp = packet.getPayload();
            final int sequenceNumber = packet.getSequenceNumber();
            final boolean isMarked = packet.isPacketMarked();
            final long timestamp = packet.getTimestamp();
            final byte payloadType = packet.getPayloadType();

            //executorService.execute(new Runnable()
            //{
            //	public void run()
            //	{
            try {
                recorder.write(rtp, 0, rtp.length, true, timestamp, false);

            } catch (Exception e) {
                Log.error("Error scanning audio", e);
            }
            //	}
            //});

        } else {
            Log.error("scan audio cannot parse packet data " + packet);
        }
    }

    /**
     *
     *
     */
    synchronized public void recordData(final RawPacket packet)
    {
        //if (snapshot < 10) Log.info("transferData " + packet.getPayloadLength() + " " + packet.getHeaderLength()  + " " + packet.getExtensionLength());

        if (packet != null)
        {
            final byte[] rtp = packet.getPayload();
            final int sequenceNumber = packet.getSequenceNumber();
            final boolean isMarked = packet.isPacketMarked();
            final long timestamp = packet.getTimestamp();

            //executorService.execute(new Runnable()
            //{
            //	public void run()
            //	{
            try {

                synchronized (_accumulator)
                {
                    if(!_sequenceNumberingViolated && _lastSequenceNumber.intValue() > -1 && Vp8Packet.getSequenceNumberDelta(sequenceNumber, _lastSequenceNumber).intValue() > 1)
                        _sequenceNumberingViolated = true;

                    _lastSequenceNumber = sequenceNumber;
                    Vp8Packet packet2 = Vp8Packet.parseBytes(rtp);

                    if(packet2 == null)	return;

                    _accumulator.add(packet2);
                    byte encodedFrame[] = null;

                    if (isMarked)
                    {
                        encodedFrame = Vp8Packet.depacketize(_accumulator.getPackets());
                        boolean isKeyframe = encodedFrame != null && encodedFrame.length > 0 && (encodedFrame[0] & 1) == 0;

                        if(_sequenceNumberingViolated && isKeyframe)
                            _sequenceNumberingViolated = false;

                        _accumulator.reset();

                        if (recorder != null && encodedFrame != null && _sequenceNumberingViolated == false)
                        {
                            byte[] full = Arrays.copyOf(encodedFrame, encodedFrame.length);

                            recorder.write(full, 0, full.length, isKeyframe, timestamp, true);

                            if (isKeyframe && snapshot < 1)
                            {
                                Log.info("recordData " + full + " " + sequenceNumber + " " + timestamp);
                                recorder.writeWebPImage(full, 0, full.length, timestamp);
                                snapshot++;
                            }
                        }
                    }
                }

            } catch (Exception e) {
                Log.error("Error writing video recording" , e);
            }
            //	}
            //});

        } else {
            Log.error("record video cannot parse packet data " + packet);
        }
    }
    /**
     *
     *
     */
    public Participant(String nickname, String focusName) {
        this.nickname = nickname;
        this.user = user;
        this.focusName = focusName;
        this.sequenceNumberingViolated = false;
        this.lastSequenceNumber = Integer.valueOf(-1);
    }
    /**
     *
     *
     */
    public String getNickname() {
        return nickname;
    }
    /**
     *
     *
     */
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
    /**
     *
     *
     */
    public String toString() {
        return user + " " + nickname;
    }
    /**
     *
     *
     */
    public JID getUser() {
        return user;
    }
    /**
     *
     *
     */
    public void setAudioStream(MediaStream mediaStream)
    {
        boolean recordMedia = true;

        if (recordMedia)
        {
            audioStream = mediaStream;
            audioStream.addPropertyChangeListener(audioStreamPropertyChangeListener);
        }
    }
    /**
     *
     *
     */
    public void setVideoStream(MediaStream mediaStream)
    {
        boolean recordMedia = true;

        if (recordMedia)
        {
            videoStream = mediaStream;
            videoStream.addPropertyChangeListener(videoStreamPropertyChangeListener);

            String recordingPath = System.getProperty("user.home");
            String fileName = "video-" + nickname + "-" + System.currentTimeMillis() + ".webm";

            try {
                recorder = new Recorder(recordingPath, fileName, "webm", false, 0, 0);

            } catch (Exception e) {
                Log.error("Error creating video recording " + fileName + " " + recordingPath, e);
            }
        }
    }

    /**
     *
     *
     */
    public void removeMediaStream()
    {
        if (audioStream != null)
        {
            audioStream.removePropertyChangeListener(audioStreamPropertyChangeListener);
        }

        if (videoStream != null)
        {
            videoStream.removePropertyChangeListener(videoStreamPropertyChangeListener);

            if (recorder != null)
            {
                recorder.done();
                recorder = null;
                snapshot = 0;
            }
        }
    }
}