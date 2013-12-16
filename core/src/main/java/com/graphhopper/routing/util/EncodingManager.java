/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.util;

import com.graphhopper.reader.OSMNode;
import com.graphhopper.reader.OSMWay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Manager class to register encoder, assign their flag values and check objects with all encoders
 * during parsing.
 * <p/>
 * @author Peter Karich
 * @author Nop
 */
public class EncodingManager
{
    public static final String CAR = "car";
    public static final String BIKE = "bike";
    public static final String FOOT = "foot";
    private static final HashMap<String, String> defaultEncoders = new HashMap<String, String>();

    static
    {
        defaultEncoders.put(CAR, CarFlagEncoder.class.getName());
        defaultEncoders.put(BIKE, BikeFlagEncoder.class.getName());
        defaultEncoders.put(FOOT, FootFlagEncoder.class.getName());
    }
    public static final int MAX_BITS = 64;
    private ArrayList<AbstractFlagEncoder> encoders = new ArrayList<AbstractFlagEncoder>();
    private int encoderCount = 0;
    private int nextBit = 0;

    public EncodingManager()
    {
    }

    /**
     * Instantiate manager with the given list of encoders. The manager knows the default encoders:
     * CAR, FOOT and BIKE (ignoring the case). Custom encoders can be specified by giving a full
     * class name e.g. "car:com.graphhopper.myproject.MyCarEncoder"
     * <p/>
     * @param encoderList comma delimited list of encoders. The order does not matter.
     */
    @SuppressWarnings("unchecked")
    public EncodingManager( String encoderList )
    {
        String[] entries = encoderList.split(",");
        Arrays.sort(entries);

        for (String entry : entries)
        {
            entry = entry.trim();
            if (entry.isEmpty())
                continue;

            String className = null;
            int pos = entry.indexOf(":");
            if (pos > 0)
            {
                className = entry.substring(pos + 1);
            } else
            {
                className = defaultEncoders.get(entry.toLowerCase());
                if (className == null)
                    throw new IllegalArgumentException("Unknown encoder name " + entry);
            }

            try
            {
                Class cls = Class.forName(className);
                register((AbstractFlagEncoder) cls.getDeclaredConstructor().newInstance());
            } catch (Exception e)
            {
                throw new IllegalArgumentException("Cannot instantiate class " + className, e);
            }
        }
    }

    protected void register( AbstractFlagEncoder encoder )
    {
        encoders.add(encoder);

        int usedBits = encoder.defineBits(encoderCount, nextBit);
        if (usedBits >= MAX_BITS)
            throw new IllegalArgumentException("Encoders are requesting more than " + MAX_BITS + " bits of flags");
        encoder.setBitMask(usedBits - nextBit, nextBit);
        nextBit = usedBits;
        encoderCount = encoders.size();
    }

    /**
     * @return true if the specified encoder is found
     */
    public boolean supports( String encoder )
    {
        return getEncoder(encoder, false) != null;
    }

    public FlagEncoder getEncoder( String name )
    {
        return getEncoder(name, true);
    }

    private FlagEncoder getEncoder( String name, boolean throwExc )
    {
        for (int i = 0; i < encoderCount; i++)
        {
            if (name.equalsIgnoreCase(encoders.get(i).toString()))
                return encoders.get(i);
        }
        if (throwExc)
            throw new IllegalArgumentException("Encoder for " + name + " not found.");
        return null;
    }

    /**
     * Determine whether an osm way is a routable way for one of its encoders.
     */
    public long acceptWay( OSMWay way )
    {
        long includeWay = 0;
        for (int i = 0; i < encoderCount; i++)
        {
            includeWay |= encoders.get(i).acceptWay(way);
        }

        return includeWay;
    }

    /**
     * Processes way properties of different kind to determine speed and direction. Properties are
     * directly encoded into 8 bytes.
     * <p/>
     * @param acceptWay return value from acceptWay
     * @return the encoded flags
     */
    public long handleWayTags( long acceptWay, OSMWay way )
    {
        long flags = 0;
        for (int i = 0; i < encoderCount; i++)
        {
            flags |= encoders.get(i).handleWayTags(acceptWay, way);
        }

        return flags;
    }

    public int getVehicleCount()
    {
        return encoderCount;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        for (int i = 0; i < encoderCount; i++)
        {
            if (str.length() > 0)
                str.append(",");

            str.append(encoders.get(i).toString());
        }

        return str.toString();
    }

    public String encoderList()
    {
        StringBuilder str = new StringBuilder();
        for (int i = 0; i < encoderCount; i++)
        {
            if (str.length() > 0)
                str.append(",");

            str.append(encoders.get(i).toString());
            str.append(":");
            str.append(encoders.get(i).getClass().getName());
        }

        return str.toString();
    }

    public FlagEncoder getSingle()
    {
        if (getVehicleCount() > 1)
            throw new IllegalStateException("multiple encoders are active. cannot return one:" + toString());

        return getFirst();
    }

    private FlagEncoder getFirst()
    {
        if (getVehicleCount() == 0)
            throw new IllegalStateException("no encoder is active!");

        return encoders.get(0);
    }

    public long flagsDefault( boolean forward, boolean backward )
    {
        long flags = 0;
        for (int i = 0; i < encoderCount; i++)
        {
            flags |= encoders.get(i).flagsDefault(forward, backward);
        }
        return flags;
    }

    /**
     * Swap direction for all encoders
     */
    public long swapDirection( long flags )
    {
        for (int i = 0; i < encoderCount; i++)
        {
            flags = encoders.get(i).swapDirection(flags);
        }
        return flags;
    }

    @Override
    public int hashCode()
    {
        int hash = 5;
        hash = 53 * hash + (this.encoders != null ? this.encoders.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals( Object obj )
    {
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        final EncodingManager other = (EncodingManager) obj;
        if (this.encoders != other.encoders && (this.encoders == null || !this.encoders.equals(other.encoders)))
        {
            return false;
        }
        return true;
    }

    /**
     * Analyze tags on osm node. Store node tags (barriers etc) for later usage while parsing way.
     */
    public long analyzeNode( OSMNode node )
    {
        long flags = 0;
        for (int i = 0; i < encoderCount; i++)
        {
            flags |= encoders.get(i).handleNodeTags(node);
        }

        return flags;
    }

    public String getWayInfo( OSMWay way )
    {
        String str = "";
        for (int i = 0; i < encoderCount; i++)
        {
            String tmpWayInfo = encoders.get(i).getWayInfo(way);
            if (tmpWayInfo.isEmpty())
                continue;
            if (!str.isEmpty())
                str += ", ";
            str += tmpWayInfo;
        }
        return str;
    }

    /**
     * When parsing the ways we have the node flags as long variable encoded in analyzeNode.
     */
    public long applyNodeFlags( long wayFlags, long nodeFlags )
    {
        long flags = 0;
        for (int i = 0; i < encoderCount; i++)
        {
            AbstractFlagEncoder encoder = encoders.get(i);
            flags |= encoder.applyNodeFlags(wayFlags & encoder.getBitMask(), nodeFlags);
        }

        return flags;
    }
}
