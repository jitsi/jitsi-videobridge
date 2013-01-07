/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.util;

import java.io.*;

import org.dom4j.*;
import org.dom4j.io.*;
import org.jivesoftware.smack.provider.*;
import org.xmlpull.v1.*;
import org.xmpp.packet.*;

/**
 * Provides functionality which aids the manipulation of
 * <tt>org.jivesoftware.smack.packet.IQ</tt> and <tt>org.xmpp.packet.IQ</tt>
 * instances.
 *
 * @author Lyubomir Marinov
 */
public final class IQUtils
{
    /**
     * The <tt>XmlPullParserFactory</tt> instance which is to create
     * <tt>XmlPullParser</tt> instances for the purposes of
     * {@link #convert(org.xmpp.packet.IQ)}. Introduced as a shared instance in
     * order to avoid unnecessary allocations.
     */
    private static XmlPullParserFactory xmlPullParserFactory;

    /**
     * Converts a specific <tt>org.jivesoftware.smack.packet.IQ</tt> instance
     * into a new <tt>org.xmpp.packet.iQ</tt> instance which represents the same
     * stanza.
     *
     * @param smackIQ the <tt>org.jivesoftware.smack.packet.IQ</tt> instance to
     * convert to a new <tt>org.xmpp.packet.IQ</tt> instance
     * @return a new <tt>org.xmpp.packet.IQ</tt> instance which represents the
     * same stanza as the specified <tt>smackIQ</tt>
     * @throws Exception if anything goes wrong during the conversion
     */
    public static org.xmpp.packet.IQ convert(
            org.jivesoftware.smack.packet.IQ smackIQ)
        throws Exception
    {
        String xml = smackIQ.getChildElementXML();
        Element element = null;

        if ((xml != null) && (xml.length() != 0))
        {
            SAXReader saxReader = new SAXReader();
            Document document = saxReader.read(new StringReader(xml));

            element = document.getRootElement();
        }

        org.xmpp.packet.IQ iq = new org.xmpp.packet.IQ();

        String from = smackIQ.getFrom();

        if ((from != null) && (from.length() != 0))
            iq.setFrom(new JID(from));
        iq.setID(smackIQ.getPacketID());

        String to = smackIQ.getTo();

        if ((to != null) && (to.length() != 0))
            iq.setTo(new JID(to));
        iq.setType(convert(smackIQ.getType()));

        if (element != null)
            iq.setChildElement(element);

        return iq;
    }

    /**
     * Converts a specific <tt>org.xmpp.packet.iQ</tt> instance into a new
     * <tt>org.jivesoftware.smack.packet.IQ</tt> instance which represents the
     * same stanza.
     *
     * @param iq the <tt>org.xmpp.packet.IQ</tt> instance to convert to a new
     * <tt>org.jivesoftware.smack.packet.IQ</tt> instance
     * @return a new <tt>org.jivesoftware.smack.packet.IQ</tt> instance which
     * represents the same stanza as the specified <tt>iq</tt>
     * @throws Exception if anything goes wrong during the conversion
     */
    public static org.jivesoftware.smack.packet.IQ convert(
            org.xmpp.packet.IQ iq)
        throws Exception
    {
        Element element = iq.getChildElement();
        IQProvider iqProvider
            = (IQProvider)
                ProviderManager.getInstance().getIQProvider(
                        element.getName(),
                        element.getNamespaceURI());
        org.jivesoftware.smack.packet.IQ smackIQ = null;

        if (iqProvider != null)
        {
            XmlPullParserFactory xmlPullParserFactory;

            synchronized (IQUtils.class)
            {
                if (IQUtils.xmlPullParserFactory == null)
                {
                    IQUtils.xmlPullParserFactory
                        = XmlPullParserFactory.newInstance();
                    IQUtils.xmlPullParserFactory.setNamespaceAware(true);
                }
                xmlPullParserFactory = IQUtils.xmlPullParserFactory;
            }

            XmlPullParser parser = xmlPullParserFactory.newPullParser();

            parser.setInput(new StringReader(iq.toXML()));

            int eventType = parser.next();

            if (XmlPullParser.START_TAG == eventType)
            {
                String name = parser.getName();

                if ("iq".equals(name))
                {
                    eventType = parser.next();
                    if (XmlPullParser.START_TAG == eventType)
                    {
                        smackIQ = iqProvider.parseIQ(parser);

                        if (smackIQ != null)
                        {
                            eventType = parser.getEventType();
                            if (XmlPullParser.END_TAG != eventType)
                            {
                                throw new IllegalStateException(
                                        Integer.toString(eventType)
                                            + " != XmlPullParser.END_TAG");
                            }
                        }
                    }
                    else
                    {
                        throw new IllegalStateException(
                                Integer.toString(eventType)
                                    + " != XmlPullParser.START_TAG");
                    }
                }
                else
                    throw new IllegalStateException(name + " != iq");
            }
            else
            {
                throw new IllegalStateException(
                        Integer.toString(eventType)
                            + " != XmlPullParser.START_TAG");
            }
        }

        if (smackIQ != null)
        {
            org.xmpp.packet.JID fromJID = iq.getFrom();

            if (fromJID != null)
                smackIQ.setFrom(fromJID.toString());
            smackIQ.setPacketID(iq.getID());

            org.xmpp.packet.JID toJID = iq.getTo();

            if (toJID != null)
                smackIQ.setTo(toJID.toString());
            smackIQ.setType(convert(iq.getType()));
        }

        return smackIQ;
    }

    /**
     * Converts an <tt>org.jivesoftware.smack.packet.IQ.Type</tt> value into an
     * <tt>org.xmpp.packet.IQ.Type</tt> value which represents the same IQ type.
     *
     * @param smackType the <tt>org.jivesoftware.smack.packet.IQ.Type</tt> value
     * to convert into an <tt>org.xmpp.packet.IQ.Type</tt> value
     * @return an <tt>org.xmpp.packet.IQ.Type</tt> value which represents the
     * same IQ type as the specified <tt>smackType</tt>
     */
    public static org.xmpp.packet.IQ.Type convert(
            org.jivesoftware.smack.packet.IQ.Type smackType)
    {
        return org.xmpp.packet.IQ.Type.valueOf(smackType.toString());
    }

    /**
     * Converts an <tt>org.xmpp.packet.IQ.Type</tt> value into an
     * <tt>org.jivesoftware.smack.packet.IQ.Type</tt> value which represents the
     * same IQ type.
     *
     * @param type the <tt>org.xmpp.packet.IQ.Type</tt> value to convert into an
     * <tt>org.jivesoftware.smack.packet.IQ.Type</tt> value
     * @return an <tt>org.jivesoftware.smack.packet.IQ.Type</tt> value which
     * represents the same IQ type as the specified <tt>type</tt>
     */
    public static org.jivesoftware.smack.packet.IQ.Type convert(
            org.xmpp.packet.IQ.Type type)
    {
        return org.jivesoftware.smack.packet.IQ.Type.fromString(type.name());
    }

    /** Prevents the initialization of new <tt>IQUtils</tt> instances. */
    private IQUtils()
    {
    }
}
