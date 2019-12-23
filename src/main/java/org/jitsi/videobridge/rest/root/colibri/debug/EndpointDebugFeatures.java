package org.jitsi.videobridge.rest.root.colibri.debug;

public enum EndpointDebugFeatures
{
    EGRESS_DUMP("egress-dump"),
    INGRESS_DUMP("ingress-dump");


    private final String value;

    EndpointDebugFeatures(String value)
    {
        this.value = value;
    }

    public String getValue()
    {
        return this.value;
    }

    /**
     * A custom 'fromString' implementation which allows creating an instance of
     * this enum from its value.  This assumes that the String values are derived using
     * the following transformation of the 'keys':
     * 1) lower case
     * 2) underscores are replaced with hyphens
     *
     * @param value the String value of the enum
     * @return an instance of the enum, if one can be derived by reversing the transformation
     * detailed above
     */
    public static DebugFeatures fromString(String value)
    {
        String normalized = value.toUpperCase().replace("-", "_");
        return DebugFeatures.valueOf(normalized);
    }
}
