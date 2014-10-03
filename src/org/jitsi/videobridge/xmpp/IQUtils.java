/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.xmpp;

import java.io.*;

import org.dom4j.*;
import org.dom4j.io.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smack.util.*;
import org.xmlpull.v1.*;
import org.xmpp.packet.*;

/**
 * Provides functionality which aids the manipulation of
 * <tt>org.jivesoftware.smack.packet.IQ</tt> and <tt>org.xmpp.packet.IQ</tt>
 * instances.
 *
 * @author Lyubomir Marinov
 */
final class IQUtils
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
        IQProvider iqProvider;

        if (element == null)
        {
            iqProvider = null;
        }
        else
        {
            iqProvider
                = (IQProvider)
                    ProviderManager.getInstance().getIQProvider(
                            element.getName(),
                            element.getNamespaceURI());
        }

        IQ.Type type = iq.getType();
        org.jivesoftware.smack.packet.IQ smackIQ = null;
        org.jivesoftware.smack.packet.XMPPError smackError = null;

        if (iqProvider != null || iq.getError() != null)
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
                    do
                    {
                        eventType = parser.next();
                        name = parser.getName();
                        if (XmlPullParser.START_TAG == eventType)
                        {
                            // 7. An IQ stanza of type "error" MAY include the
                            // child element contained in the associated "get"
                            // or "set" and MUST include an <error/> child.
                            if (IQ.Type.error.equals(type)
                                    && "error".equals(name))
                            {
                                smackError
                                    = PacketParserUtils.parseError(parser);
                            }
                            else if (smackIQ == null && iqProvider != null)
                            {
                                smackIQ = iqProvider.parseIQ(parser);
                                if (smackIQ != null
                                        && XmlPullParser.END_TAG
                                                != parser.getEventType())
                                {
                                    throw new IllegalStateException(
                                        Integer.toString(eventType)
                                            + " != XmlPullParser.END_TAG");
                                }
                            }
                        }
                        else if ((XmlPullParser.END_TAG == eventType
                                        && "iq".equals(name))
                                || (smackIQ != null && smackError != null)
                                || XmlPullParser.END_DOCUMENT == eventType)
                        {
                            break;
                        }
                    }
                    while (true);

                    eventType = parser.getEventType();
                    if (XmlPullParser.END_TAG != eventType)
                    {
                        throw new IllegalStateException(
                                Integer.toString(eventType)
                                    + " != XmlPullParser.END_TAG");
                    }
                }
                else
                {
                    throw new IllegalStateException(name + " != iq");
                }
            }
            else
            {
                throw new IllegalStateException(
                        Integer.toString(eventType)
                            + " != XmlPullParser.START_TAG");
            }
        }

        // 6. An IQ stanza of type "result" MUST include zero or one child
        // elements.
        // 7. An IQ stanza of type "error" MAY include the child element
        // contained in the associated "get" or "set" and MUST include an
        // <error/> child.
        if (smackIQ == null
                && (IQ.Type.error.equals(type) || IQ.Type.result.equals(type)))
        {
            smackIQ
                = new org.jivesoftware.smack.packet.IQ()
                {
                    @Override
                    public String getChildElementXML()
                    {
                        return "";
                    }
                };
        }

        if (smackIQ != null)
        {
            // from
            org.xmpp.packet.JID fromJID = iq.getFrom();

            if (fromJID != null)
                smackIQ.setFrom(fromJID.toString());
            // id
            smackIQ.setPacketID(iq.getID());

            // to
            org.xmpp.packet.JID toJID = iq.getTo();

            if (toJID != null)
                smackIQ.setTo(toJID.toString());
            // type
            smackIQ.setType(convert(type));

            if (smackError != null)
                smackIQ.setError(smackError);
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
