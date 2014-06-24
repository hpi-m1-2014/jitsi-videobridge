/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.beans.*;
import java.beans.beancontext.*;
import java.io.*;
import java.lang.ref.*;
import java.text.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;

import net.java.sip.communicator.util.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger;
import org.jitsi.util.event.*;
import org.osgi.framework.*;

/**
 * Represents a conference in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class Conference
     extends PropertyChangeNotifier
     implements PropertyChangeListener
{
    /**
     * The name of the <tt>Conference</tt> property <tt>endpoints</tt> which
     * lists the <tt>Endpoint</tt>s participating in/contributing to the
     * <tt>Conference</tt>.
     */
    public static final String ENDPOINTS_PROPERTY_NAME
        = Conference.class.getName() + ".endpoints";

    /**
     * The <tt>Logger</tt> used by the <tt>Conference</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Conference.class);

    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level 
     */
    private static void logd(String s)
    {
        /*
         * FIXME Jitsi Videobridge uses the defaults of java.util.logging at the
         * time of this writing but wants to log at debug level at all times for
         * the time being in order to facilitate early development.
         */
        logger.info(s);
    }

    /**
     * The <tt>Content</tt>s of this <tt>Conference</tt>.
     */
    private final List<Content> contents = new LinkedList<Content>();

    /**
     * The <tt>Endpoint</tt>s participating in this <tt>Conference</tt>.
     */
    private final List<WeakReference<Endpoint>> endpoints
        = new LinkedList<WeakReference<Endpoint>>();

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Conference</tt>.
     */
    private boolean expired = false;

    /**
     * The JID of the conference focus who has initialized this instance and
     * from whom requests to manage this instance must come or they will be
     * ignored. If <tt>null</tt> value is assigned we don't care who modifies
     * the conference.
     */
    private final String focus;

    /**
     * The (unique) identifier/ID of this instance.
     */
    private final String id;

    /**
     * The time in milliseconds of the last activity related to this
     * <tt>Conference</tt>. In the time interval between the last activity and
     * now, this <tt>Conference</tt> is considered inactive.
     */
    private long lastActivityTime;

    private final PropertyChangeListener propertyChangeListener;

    /**
     * The speech activity (representation) of the <tt>Endpoint</tt>s of this
     * <tt>Conference</tt>.
     */
    private final ConferenceSpeechActivity speechActivity;

    /**
     * The <tt>Videobridge</tt> which has initialized this <tt>Conference</tt>.
     */
    private final Videobridge videobridge;

    /**
     * Whether media recording is currently enabled for this <tt>Conference</tt>.
     */
    private boolean recording = false;

    /**
     * The <tt>RecorderEventHandler</tt> which is used to handle recording
     * events for this <tt>Conference</tt>.
     */
    private RecorderEventHandler recorderEventHandler = null;

    /**
     * The path to the directory into which files associated with media
     * recordings for this <tt>Conference</tt> will be stored.
     */
    private String recordingPath = null;

    /**
     * Initializes a new <tt>Conference</tt> instance which is to represent a
     * conference in the terms of Jitsi Videobridge which has a specific
     * (unique) ID and is managed by a conference focus with a specific JID.
     *
     * @param videobridge the <tt>Videobridge</tt> on which the new
     * <tt>Conference</tt> instance is to be initialized
     * @param id the (unique) ID of the new instance to be initialized
     * @param focus the JID of the conference focus who has requested the
     * initialization of the new instance and from whom further/future requests
     * to manage the new instance must come or they will be ignored.
     * Pass <tt>null</tt> to override this safety check.
     */
    public Conference(Videobridge videobridge,
                      String id,
                      String focus)
    {
        if (videobridge == null)
            throw new NullPointerException("videobridge");
        if (id == null)
            throw new NullPointerException("id");

        this.videobridge = videobridge;
        this.id = id;
        this.focus = focus;

        propertyChangeListener = new WeakReferencePropertyChangeListener(this);
        speechActivity = new ConferenceSpeechActivity(this);
        speechActivity.addPropertyChangeListener(propertyChangeListener);
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is deep i.e. the
     * <tt>Contents</tt>s of this instance are described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeDeep(ColibriConferenceIQ iq)
    {
        describeShallow(iq);

        if (isRecording())
        {
            ColibriConferenceIQ.Recording recordingIQ
                    = new ColibriConferenceIQ.Recording(true);
            recordingIQ.setPath(getRecordingPath());
            iq.setRecording(recordingIQ);
        }
        for (Content content : getContents())
        {
            ColibriConferenceIQ.Content contentIQ
                = iq.getOrCreateContent(content.getName());

            for (Channel channel : content.getChannels())
            {
                ColibriConferenceIQ.Channel channelIQ
                    = new ColibriConferenceIQ.Channel();

                channel.describe(channelIQ);
                contentIQ.addChannel(channelIQ);
            }
        }
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is shallow i.e. the
     * <tt>Content</tt>s of this instance are not described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeShallow(ColibriConferenceIQ iq)
    {
        iq.setID(getID());
    }

    /**
     * Notifies this instance that {@link #speechActivity} has identified a
     * speaker switch event in this multipoint conference and there is now a new
     * dominant speaker.
     */
    private void dominantSpeakerChanged()
    {
        Endpoint dominantSpeaker = speechActivity.getDominantEndpoint();

        logd(
                "The dominant speaker in conference " + getID()
                    + " is now the endpoint "
                    + ((dominantSpeaker == null)
                        ? "(null)"
                        : dominantSpeaker.getID())
                    + ".");

        if (dominantSpeaker != null)
        {
            broadcastMessage("activeSpeaker:" + dominantSpeaker.getID());
        }
    }

    /**
     * Expires this <tt>Conference</tt>, its <tt>Content</tt>s and their
     * respective <tt>Channel</tt>s. Releases the resources acquired by this
     * instance throughout its life time and prepares it to be garbage
     * collected.
     */
    public void expire()
    {
        synchronized (this)
        {
            if (expired)
                return;
            else
                expired = true;
        }


        setRecording(false);
        if (recorderEventHandler != null)
        {
            recorderEventHandler.close();
            recorderEventHandler = null;
        }

        Videobridge videobridge = getVideobridge();

        try
        {
            videobridge.expireConference(this);
        }
        finally
        {
            // Expire the Contents of this Conference.
            for (Content content : getContents())
            {
                try
                {
                    content.expire();
                }
                catch (Throwable t)
                {
                    logger.warn(
                            "Failed to expire content " + content.getName()
                                + " of conference " + getID() + "!",
                            t);
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                }
            }

            logd(
                    "Expired conference " + getID() + ". The total number of"
                        + " conferences is now "
                        + videobridge.getConferenceCount() + ", channels "
                        + videobridge.getChannelCount() + ".");
        }
    }

    /**
     * Expires a specific <tt>Content</tt> of this <tt>Conference</tt> (i.e. if
     * the specified <tt>content</tt> is not in the list of <tt>Content</tt>s of
     * this <tt>Conference</tt>, does nothing).
     *
     * @param content the <tt>Content</tt> to be expired by this
     * <tt>Conference</tt>
     */
    public void expireContent(Content content)
    {
        boolean expireContent;

        synchronized (contents)
        {
            if (contents.contains(content))
            {
                contents.remove(content);
                expireContent = true;
            }
            else
                expireContent = false;
        }
        if (expireContent)
            content.expire();
    }

    /**
     * Finds a <tt>Channel</tt> of this <tt>Conference</tt> which receives a
     * specific SSRC and is with a specific <tt>MediaType</tt>.
     *
     * @param receiveSSRC the SSRC of a received RTP stream whose receiving
     * <tt>Channel</tt> in this <tt>Conference</tt> is to be found
     * @param mediaType the <tt>MediaType</tt> of the <tt>Channel</tt> to be
     * found
     * @return the <tt>Channel</tt> in this <tt>Conference</tt> which receives
     * the specified <tt>ssrc</tt> and is with the specified <tt>mediaType</tt>;
     * otherwise, <tt>null</tt>
     */
    Channel findChannelByReceiveSSRC(long receiveSSRC, MediaType mediaType)
    {
        for (Content content : getContents())
        {
            if (mediaType.equals(content.getMediaType()))
            {
                Channel channel = content.findChannelByReceiveSSRC(receiveSSRC);
                if (channel != null)
                    return channel;
            }
        }
        return null;
    }

    /**
     * Finds an <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with a specific SSRC and with a specific <tt>MediaType</tt>.
     *
     * @param receiveSSRC the SSRC of an RTP stream received by this
     * <tt>Conference</tt> whose sending <tt>Endpoint</tt> is to be found
     * @param mediaType the <tt>MediaType</tt> of the RTP stream identified by
     * the specified <tt>ssrc</tt>
     * @return <tt>Endpoint</tt> of this <tt>Conference</tt> which sends an RTP
     * stream with the specified <tt>ssrc</tt> and with the specified
     * <tt>mediaType</tt>; otherwise, <tt>null</tt>
     */
    Endpoint findEndpointByReceiveSSRC(long receiveSSRC, MediaType mediaType)
    {
        Channel channel = findChannelByReceiveSSRC(receiveSSRC, mediaType);

        return (channel == null) ? null : channel.getEndpoint();
    }

    /**
     * Returns the OSGi <tt>BundleContext</tt> in which this Conference is
     * executing.
     *
     * @return the OSGi <tt>BundleContext</tt> in which the Conference is
     * executing.
     */
    public BundleContext getBundleContext()
    {
        return getVideobridge().getBundleContext();
    }

    /**
     * Gets the <tt>Content</tt>s of this <tt>Conference</tt>.
     *
     * @return the <tt>Content</tt>s of this <tt>Conference</tt>
     */
    public Content[] getContents()
    {
        synchronized (contents)
        {
            return contents.toArray(new Content[contents.size()]);
        }
    }

    /**
     * Gets the <tt>Endpoint</tt>s participating in/contributing to this
     * <tt>Conference</tt>.
     *
     * @return the <tt>Endpoint</tt>s participating in/contributing to this
     * <tt>Conference</tt>
     */
    public List<Endpoint> getEndpoints()
    {
        List<Endpoint> endpoints;
        boolean changed = false;

        synchronized (this.endpoints)
        {
            endpoints = new ArrayList<Endpoint>(this.endpoints.size());

            for (Iterator<WeakReference<Endpoint>> i
                        = this.endpoints.iterator();
                    i.hasNext();)
            {
                Endpoint endpoint = i.next().get();

                if (endpoint == null)
                {
                    i.remove();
                    changed = true;
                }
                else
                {
                    endpoints.add(endpoint);
                }
            }
        }

        if (changed)
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);

        return endpoints;
    }

    /**
     * Gets the JID of the conference focus who has initialized this instance
     * and from whom requests to manage this instance must come or they will be
     * ignored.
     *
     * @return the JID of the conference focus who has initialized this instance
     * and from whom requests to manage this instance must come or they will be
     * ignored
     */
    public final String getFocus()
    {
        return focus;
    }

    /**
     * Gets the (unique) identifier/ID of this instance.
     *
     * @return the (unique) identifier/ID of this instance
     */
    public final String getID()
    {
        return id;
    }

    /**
     * Gets the time in milliseconds of the last activity related to this
     * <tt>Conference</tt>.
     *
     * @return the time in milliseconds of the last activity related to this
     * <tt>Conference</tt>
     */
    public long getLastActivityTime()
    {
        synchronized (this)
        {
            return lastActivityTime;
        }
    }

    /**
     * Gets a <tt>Content</tt> of this <tt>Conference</tt> which has a specific
     * name. If a <tt>Content</tt> of this <tt>Conference</tt> with the
     * specified <tt>name</tt> does not exist at the time the method is invoked,
     * the method initializes a new <tt>Content</tt> instance with the specified
     * <tt>name</tt> and adds it to the list of <tt>Content</tt>s of this
     * <tt>Conference</tt>.
     *
     * @param name the name of the <tt>Content</tt> which is to be returned
     * @return a <tt>Content</tt> of this <tt>Conference</tt> which has the
     * specified <tt>name</tt>
     */
    public Content getOrCreateContent(String name)
    {
        Content content;

        synchronized (contents)
        {
            for (Content aContent : contents)
            {
                if (aContent.getName().equals(name))
                {
                    aContent.touch(); // It seems the content is still active.
                    return aContent;
                }
            }

            content = new Content(this, name);
            if (isRecording())
                content.setRecording(true, getRecordingPath());
            contents.add(content);
        }

        /*
         * The method Videobridge.getChannelCount() should better be executed
         * outside synchronized blocks in order to reduce the risks of causing
         * deadlocks.
         */
        Videobridge videobridge = getVideobridge();

        logd(
                "Created content " + name + " of conference " + getID()
                    + ". The total number of conferences is now "
                    + videobridge.getConferenceCount() + ", channels "
                    + videobridge.getChannelCount() + ".");

        return content;
    }

    /**
     * Gets an <tt>Endpoint</tt> participating in this <tt>Conference</tt> which
     * has a specific identifier/ID. If an <tt>Endpoint</tt> participating in
     * this <tt>Conference</tt> with the specified <tt>id</tt> does not exist at
     * the time the method is invoked, the method initializes a new
     * <tt>Endpoint</tt> instance with the specified <tt>id</tt> and adds it to
     * the list of <tt>Endpoint</tt>s participating in this <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the <tt>Endpoint</tt> which is to be
     * returned
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     * which has the specified <tt>id</tt>
     */
    public Endpoint getOrCreateEndpoint(String id)
    {
        Endpoint endpoint = null;
        boolean changed = false;

        synchronized (endpoints)
        {
            for (Iterator<WeakReference<Endpoint>> i = endpoints.iterator();
                    i.hasNext();)
            {
                Endpoint e = i.next().get();
                if (e == null)
                {
                    i.remove();
                    changed = true;
                }
                else if (e.getID().equals(id))
                {
                    endpoint = e;
                }
            }

            if (endpoint == null)
            {
                endpoint = new Endpoint(id);
                /*
                 * The propertyChangeListener will weakly reference this
                 * Conference and will unregister itself from the endpoint
                 * sooner or later.
                 */
                endpoint.addPropertyChangeListener(propertyChangeListener);

                endpoints.add(new WeakReference<Endpoint>(endpoint));
                changed = true;
            }
        }

        if (changed)
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);

        return endpoint;
    }

    /**
     * Gets the speech activity (representation) of the <tt>Endpoint</tt>s of
     * this <tt>Conference</tt>.
     *
     * @return the speech activity (representation) of the <tt>Endpoint</tt>s of
     * this <tt>Conference</tt>
     */
    ConferenceSpeechActivity getSpeechActivity()
    {
        return speechActivity;
    }

    /**
     * Gets the <tt>Videobridge</tt> which has initialized this
     * <tt>Conference</tt>.
     *
     * @return the <tt>Videobridge</tt> which has initialized this
     * <tt>Conference</tt>
     */
    public final Videobridge getVideobridge()
    {
        return videobridge;
    }

    /**
     * Notifies this instance that there was a change in the value of a property
     * of an object in which this instance is interested.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the object of
     * interest, the name of the property and the old and new values of that
     * property
     */
    public void propertyChange(PropertyChangeEvent ev)
    {
        Object source = ev.getSource();

        if (speechActivity == source)
        {
            String propertyName = ev.getPropertyName();

            if (ConferenceSpeechActivity.DOMINANT_ENDPOINT_PROPERTY_NAME.equals(
                    propertyName))
            {
                dominantSpeakerChanged();
            }
            else if (ConferenceSpeechActivity.ENDPOINTS_PROPERTY_NAME.equals(
                    propertyName))
            {
                speechActivityEndpointsChanged();
            }
        }
    }

    private void speechActivityEndpointsChanged()
    {
        List<Endpoint> endpoints = null;

        for (Content content : getContents())
        {
            if (MediaType.VIDEO.equals(content.getMediaType()))
            {
                Set<Endpoint> endpointsToAskForKeyframes = null;

                endpoints = speechActivity.getEndpoints();
                for (Channel channel : content.getChannels())
                {
                    //FIXME: remove instance of
                    if (!(channel instanceof RtpChannel))
                    {
                        continue;
                    }

                    RtpChannel rtpChannel = (RtpChannel) channel;

                    List<Endpoint> channelEndpointsToAskForKeyframes
                        = rtpChannel.lastNEndpointsChanged(endpoints);

                    if ((channelEndpointsToAskForKeyframes != null)
                            && !channelEndpointsToAskForKeyframes.isEmpty())
                    {
                        if (endpointsToAskForKeyframes == null)
                        {
                            endpointsToAskForKeyframes
                                = new HashSet<Endpoint>();
                        }
                        endpointsToAskForKeyframes.addAll(
                                channelEndpointsToAskForKeyframes);
                    }
                }

                if ((endpointsToAskForKeyframes != null)
                        && !endpointsToAskForKeyframes.isEmpty())
                {
                    content.askForKeyframes(endpointsToAskForKeyframes);
                }
            }
        }
    }

    /**
     * Sets the time in milliseconds of the last activity related to this
     * <tt>Conference</tt> to the current system time.
     */
    public void touch()
    {
        long now = System.currentTimeMillis();

        synchronized (this)
        {
            if (getLastActivityTime() < now)
                lastActivityTime = now;
        }
    }

    /**
     * Broadcasts string message to al participants over default data channel.
     *
     * @param msg the message to be advertised across conference peers.
     */
    private void broadcastMessage(String msg)
    {
        ArrayList<WeakReference<Endpoint>> endpointsCopy;

        synchronized (endpoints)
        {
            endpointsCopy
                = new ArrayList<WeakReference<Endpoint>>(endpoints);
        }

        int endpointsCount = endpointsCopy.size();

        if(endpointsCount == 0)
            return;

        for(WeakReference<Endpoint> endpoint : endpoints)
        {
            Endpoint toNotify = endpoint.get();
            if(toNotify == null)
                continue;

            sendMessageOnDataChannel(toNotify, msg);
        }
    }

    /**
     * Sends given <tt>String</tt> <tt>msg</tt> to given <tt>endpoint</tt>
     * over default data channel.
     *
     * @param endpoint message recipient.
     * @param msg message text to be sent.
     */
    private void sendMessageOnDataChannel(Endpoint endpoint, String msg)
    {
        String endpointId = endpoint.getID();

        SctpConnection sctpConnection = endpoint.getSctpConnection();

        if(sctpConnection == null)
        {
            logger.warn("No SCTP connection with " + endpointId);
            return;
        }

        if(!sctpConnection.isReady())
        {
            logger.warn(
                "SCTP connection with " + endpointId + " not ready yet");
            return;
        }

        try
        {
            WebRtcDataStream dataStream
                = sctpConnection.getDefaultDataStream();

            if(dataStream == null)
            {
                logger.warn(
                    "WebRtc data channel not opened yet " + endpointId);
                return;
            }

            dataStream.sendString(msg);
        }
        catch (IOException e)
        {
            logger.error("SCTP error, endpoint: " + endpointId, e);
        }
    }

    /**
     * Checks whether media recording is currently enabled for this
     * <tt>Conference</tt>.
     * @return <tt>true</tt> if media recording is currently enabled for this
     * <tt>Conference</tt>, false otherwise.
     */
    public boolean isRecording()
    {
        boolean recording = this.recording;

        //if one of the contents is not recording, stop all recording
        if (recording)
        {
            synchronized (contents)
            {
                for (Content content : contents)
                    if (!content.isRecording())
                        recording = false;
            }
        }
        if (this.recording != recording)
            setRecording(recording);

        return this.recording;
    }

    /**
     * Attempts to enable or disable media recording for this
     * <tt>Conference</tt>.
     *
     * @param recording whether to enable or disable recording.
     * @return the state of the media recording for this <tt>Conference</tt>
     * after the attempt to enable (or disable).
     */
    public boolean setRecording(boolean recording)
    {
        if (recording != this.recording)
        {
            if (recording)
            {
                //try enable recording
                logd("Starting recording for conference with id=" + getID());
                boolean failedToStart;

                String path = getRecordingPath();
                failedToStart = !checkRecordingDirectory(path);

                if (!failedToStart)
                {
                    RecorderEventHandler handler = getRecorderEventHandler();
                    if (handler == null)
                        failedToStart = true;
                }

                /*
                 * The Recorders of the Contents need to share a single
                 * Synchronizer, we take it from the first Recorder.
                 */
                boolean first = true;
                Synchronizer synchronizer = null;
                for (Content content : contents)
                {
                    if (!failedToStart)
                        failedToStart |= !content.setRecording(true, path);
                    if (failedToStart)
                        break;

                    if (first)
                    {
                        first = false;
                        synchronizer = content.getRecorder().getSynchronizer();
                    }
                    else
                        content.getRecorder().setSynchronizer(synchronizer);

                    content.feedKnownSsrcsToSynchronizer();
                }


                if (failedToStart)
                {
                    recording = false;
                    logger.warn("Failed to start media recording for conference "
                                        + getID());
                }
            }

            // either we were asked to disable recording, or we failed to
            // enable it
            if (!recording)
            {
                logd("Stopping recording for conference with id=" + getID());

                for (Content content : contents)
                {
                    content.setRecording(false, null);
                }

                if (recorderEventHandler != null)
                    recorderEventHandler.close();
                recorderEventHandler = null;
                recordingPath = null;
            }

            this.recording = recording;
        }

        return this.recording;
    }

    /**
     * Returns the path to the directory where the media recording related
     * files should be saved, or <tt>null</tt> if recording is not enabled
     * in the configuration, or a recording path has not been configured.
     *
     * @return the path to the directory where the media recording related
     * files should be saved, or <tt>null</tt> if recording is not enabled
     * in the configuration, or a recording path has not been configured.
     */
    String getRecordingPath()
    {
        if (recordingPath == null)
        {
            ConfigurationService cfg
                    = getVideobridge().getConfigurationService();
            if (cfg == null)
                return null;
            boolean recordingEnabled
                    = cfg.getBoolean(Videobridge.ENABLE_MEDIA_RECORDING_PNAME,
                                     false);
            //if (!recordingEnabled)
                //return null;
            String path
                    = cfg.getString(Videobridge.MEDIA_RECORDING_PATH_PNAME, null);
            if (path == null)
                path = "/Users/nicholas/recording";
                //return null;

            this.recordingPath = path + "/" + getID()
                    + (new SimpleDateFormat("-yyMMdd-HHmmss")
                            .format(new Date()));
        }

        return recordingPath;
    }

    /**
     * Checks whether <tt>path</tt> is a valid directory for recording (creates
     * it if necessary).
     * @param path the path to the directory to check.
     * @return <tt>true</tt> if the directory <tt>path</tt> can be used for
     * media recording, <tt>false</tt> otherwise.
     */
    private boolean checkRecordingDirectory(String path)
    {
        if (path == null || "".equals(path))
            return false;

        File dir = new File(path);
        if (!dir.exists())
            dir.mkdir();
        if (!dir.exists())
            return false;

        if (!dir.isDirectory() || !dir.canWrite())
            return false;

        return true;
    }

    RecorderEventHandler getRecorderEventHandler()
    {
        if (recorderEventHandler == null)
        {

            try
            {
                recorderEventHandler
                        = new RecorderEventHandlerImpl(
                        getMediaService()
                                .createRecorderEventHandlerJson(
                                        getRecordingPath() + "/metadata.json"));
            }
            catch (IOException ioe)
            {
                logger.warn("Could not create RecorderEventHandler. " + ioe);
            }
            catch (IllegalArgumentException iae)
            {
                logger.warn("Could not create RecorderEventHandlerImpl. " + iae);
            }
        }

        return recorderEventHandler;
    }

    /**
     * Returns a <tt>MediaService</tt> implementation (if any).
     *
     * @return a <tt>MediaService</tt> implementation (if any)
     */
    MediaService getMediaService()
    {
        MediaService mediaService
                = ServiceUtils.getService(getBundleContext(), MediaService.class);

    /*
     * TODO For an unknown reason, ServiceUtils.getService fails to retrieve
     * the MediaService implementation. In the form of a temporary
     * workaround, get it through LibJitsi.
     */
        if (mediaService == null)
            mediaService = LibJitsi.getMediaService();

        return mediaService;
    }


    /**
     * XXX REMOVE
     * Finds all the SSRCs received on all video <tt>Channel</tt> in this
     * <tt>Conference</tt>, which are associated in an
     * <tt>Endpoint</tt> with an audio <tt>Channel</tt> on which
     * <tt>audioSsrc</tt> is received. If none is found, returns <tt>-1</tt>.
     *
     * Assumes that <tt>audioSsrc</tt> can be received on no more than a single
     * <tt>Channel</tt>.
     *
     * @param audioSsrc the audio SSRC.
     * @return a <tt>List</tt> of received video SSRCs associated with
     * <tt>audioSsrc</tt>
     */
    private List<Long> getVideoSsrcs(long audioSsrc)
    {
        List<Long> videoSsrcs = new LinkedList<Long>();
        Channel audioChannel = null;

        for(Content c : getContents())
        {
            if (MediaType.AUDIO.equals(c.getMediaType())
                    && (audioChannel = c.findChannel(audioSsrc)) != null)
                break;
        }

        if (audioChannel != null)
        {
            Endpoint endpoint = audioChannel.getEndpoint();
            if (endpoint != null)
            {
                for (Channel channel : endpoint.getChannels(MediaType.VIDEO))
                {
                    if (channel instanceof RtpChannel)
                        for (int ssrc : ((RtpChannel) channel).getReceiveSSRCs())
                            videoSsrcs.add(0xffffffffL & ssrc);
                }
            }
        }

        return videoSsrcs;
    }

    /**
     * An implementation of <tt>RecorderEventHandler</tt> which intercepts
     * <tt>SPEAKER_CHANGED</tt> events and updates their 'ssrc' fields
     * (which contain the SSRC of a video stream) before delegating to
     * another underlying <tt>RecorderEventHandler</tt>.
     * The value to use for an event's 'ssrc' field is a value found to be
     * associated with the audio SSRC of the event (the 'audioSsrc' field).
     */
    private class RecorderEventHandlerImpl
            implements RecorderEventHandler
    {
        private RecorderEventHandler handler;

        RecorderEventHandlerImpl(RecorderEventHandler handler)
                throws IllegalArgumentException
        {
            if (handler == null)
                throw new IllegalArgumentException("handler is null");
            this.handler = handler;
        }

        @Override
        public boolean handleEvent(RecorderEvent event)
        {
            if (RecorderEvent.Type.SPEAKER_CHANGED
                    .equals(event.getType()))
            {
                long audioSsrc = event.getAudioSsrc();
                List<Long> videoSsrcs = getVideoSsrcs(audioSsrc);
                if (videoSsrcs.isEmpty())
                {
                    logd("Could not find video SSRC associated with audioSsrc="
                                 + audioSsrc);

                    //don't write events without proper 'ssrc' values
                    return false;
                }

                //for the moment just use the first SSRC
                event.setSsrc(videoSsrcs.get(0));
            }
            return handler.handleEvent(event);
        }

        @Override
        public void close()
        {
            handler.close();
        }
    }
}
