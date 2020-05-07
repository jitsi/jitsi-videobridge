package org.jitsi.videobridge;

public class Constraints
{
    private final int idealHeight;

    public Constraints(int idealHeight)
    {
        this.idealHeight = idealHeight;
    }

    public int getIdealHeight()
    {
        return idealHeight;
    }

    static Constraints makeMaxHeightEndpointConstraints(int idealHeight)
    {
        return new Constraints(idealHeight);
    }

    public EndpointConstraints toEndpointConstraints(String id)
    {
        return new EndpointConstraints(id, idealHeight);
    }
}
