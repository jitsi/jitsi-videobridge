#!/usr/bin/perl -w

use Statistics::Descriptive;
use strict;

my %stats;
my @stat_names;

=begin comment

This is extremely ugly, I'm driving perl in first gear because I don't know how to shift :)

It parses the PacketInfo EventTimeline logs (see example below) and produces stats for 
the following periods from the life cycle of a packet:

1.Receiver Queue: From the moment the packet is read from the ICE transport (in an IO thread) and added the the Receiver Queue
to the moment it is removed from the Receiver Queue (in a CPU thread) and the pipeline processing starts.

2.Receiver Pipeline: From the start of the Receiver Pipeline to the end, excluding the last Termination node. This is all
performed in the same CPU thread, and includes things like SRTP decryption/authentication, RTP parsing, updating bandwidth
estimation, video codec parsing, etc.

3.Termination Node: This is technically the last Node in the Receiver Pipeline and executes in the same thread, but
it is conceptually different and worth looking at separately. It consists of conference-level handling of the packet: it loops
through all local endpoints and relays, checks whether they "want" the packet, and if they do then clones the packet and puts
the clone on the sender queue (see Conference#sendOut). This workload scales with the number of local endpoits and relays,
and in a large conference is the most computationally expensive.

4.Sender Queue: From the time the Receiver Pipeline thread places the packet on the Sender Queue, to the time another CPU thread
removes it from the queue and starts executing the Sender Pipeline.

5.Sender Pipeline: From the start of the Sender Pipeline to the end. This executes in a CPU thread and includes stripping unknown (for
this specific receiving endpoint) RTP header extensions, saving a copy of the packet in the NACK cache, adding/updating the transport-cc
header extension, and SRTP encryption.

This script looks only at RTP packets (ignoring RTCP and retransmissions) for simplicity.

6.SRTP Queue: From the time a SRTP packet is placed on the queue by the Sender Pipeline thread to the time an IO thread removes it
from the queue and starts sending it over the ICE transport.

7.Send over ICE transport: The time it took to execute IceTransport.send, which sends the packet through the ice4j socket representation.

8.Total: From the start of 1. to the end of 7.



Example log line:
JVB 2024-06-11 17:57:44.989 FINER: [1531] [confId=6149c0c339432f97 conf_name=loadtest0@conference.xxx meeting_id=ec7261c3 epId=c88aca35 stats_id=Otto-emV] Endpoint.doSendSrtp#544: Reference time: 2024-06-11T17:57:44.988517466Z; (Entered RTP receiver incoming queue, PT0.00000192S); (Exited RTP receiver incoming queue, PT0.000324242S); (Entered node PacketStreamStats, PT0.000324602S); (Exited node PacketStreamStats, PT0.000325042S); (Entered node SRTP/SRTCP demuxer, PT0.000325162S); (Exited node SRTP/SRTCP demuxer, PT0.000325722S); (Entered node RTP Parser, PT0.000325962S); (Exited node RTP Parser, PT0.000326762S); (Entered node Audio level reader (pre-srtp), PT0.000326922S); (Exited node Audio level reader (pre-srtp), PT0.000327242S); (Entered node Video mute node, PT0.000327362S); (Exited node Video mute node, PT0.000327642S); (Entered node SRTP Decrypt Node, PT0.000327762S); (Exited node SRTP Decrypt Node, PT0.000332442S); (Entered node TCC generator, PT0.000332562S); (Exited node TCC generator, PT0.000333562S); (Entered node Remote Bandwidth Estimator, PT0.000344962S); (Exited node Remote Bandwidth Estimator, PT0.000345202S); (Entered node Audio level reader (post-srtp), PT0.000345322S); (Exited node Audio level reader (post-srtp), PT0.000345442S); (Entered node Toggleable pcap writer: 21913831-rx, PT0.000345562S); (Exited node Toggleable pcap writer: 21913831-rx, PT0.000345762S); (Entered node Incoming statistics tracker, PT0.000345882S); (Exited node Incoming statistics tracker, PT0.000351242S); (Entered node Padding termination, PT0.000351362S); (Exited node Padding termination, PT0.000351522S); (Entered node Media Type demuxer, PT0.000351642S); (Exited node Media Type demuxer, PT0.000351962S); (Entered node RTX handler, PT0.000352082S); (Exited node RTX handler, PT0.000352322S); (Entered node Duplicate termination, PT0.000352722S); (Exited node Duplicate termination, PT0.000355442S); (Entered node Retransmission requester, PT0.000355882S); (Exited node Retransmission requester, PT0.000357922S); (Entered node Padding-only discarder, PT0.000358642S); (Exited node Padding-only discarder, PT0.000359842S); (Entered node Video parser, PT0.000360242S); (Exited node Video parser, PT0.000363082S); (Entered node Video quality layer lookup, PT0.000363362S); (Exited node Video quality layer lookup, PT0.000364362S); (Entered node Video bitrate calculator, PT0.000364682S); (Exited node Video bitrate calculator, PT0.000369122S); (Entered node Input pipeline termination node, PT0.000369362S); (Entered node receiver chain handler, PT0.000370122S); (Entered RTP sender incoming queue, PT0.000488083S); (Exited RTP sender incoming queue, PT0.000551283S); (Entered node Pre-processor, PT0.000551723S); (Exited node Pre-processor, PT0.000555843S); (Entered node RedHandler, PT0.000556083S); (Exited node RedHandler, PT0.000556563S); (Entered node Strip header extensions, PT0.000556843S); (Exited node Strip header extensions, PT0.000557523S); (Entered node Packet cache, PT0.000557923S); (Exited node Packet cache, PT0.000561083S); (Entered node Absolute send time, PT0.000561243S); (Exited node Absolute send time, PT0.000561643S); (Entered node Outgoing statistics tracker, PT0.000561923S); (Exited node Outgoing statistics tracker, PT0.000562683S); (Entered node TCC sequence number tagger, PT0.000563003S); (Exited node TCC sequence number tagger, PT0.000563843S); (Entered node Header extension encoder, PT0.000564083S); (Exited node Header extension encoder, PT0.000565163S); (Entered node Toggleable pcap writer: c88aca35-tx, PT0.000565443S); (Exited node Toggleable pcap writer: c88aca35-tx, PT0.000565763S); (Entered node SRTP Encrypt Node, PT0.000566083S); (Exited node SRTP Encrypt Node, PT0.000572883S); (Entered node PacketStreamStats, PT0.000573243S); (Exited node PacketStreamStats, PT0.000573883S); (Entered node Output pipeline termination node, PT0.000574123S); (Entered Endpoint SRTP sender outgoing queue, PT0.000574443S); (Exited node Output pipeline termination node, PT0.000582923S); (Exited Endpoint SRTP sender outgoing queue, PT0.000668003S); (Sent over the ICE transport, PT0.000693284S)

=end comment

=cut

my $receiverQStartName = "Entered RTP receiver incoming queue";
my $receiverQEndName = "Exited RTP receiver incoming queue";
my $receiverPStartName = "Entered node SRTP/SRTCP demuxer";
my $receiverPEndName = "Entered node Input pipeline termination node";
my $terminationNodeStartName = "Entered node Input pipeline termination node";
my $terminationNodeEndName = "Exited node Input pipeline termination node";
my $senderQStartName = "Entered RTP sender incoming queue";
my $senderQEndName = "Exited RTP sender incoming queue";
my $senderPStartName = "Entered node RedHandler";
my $senderPEndName = "Entered Endpoint SRTP sender outgoing queue";
my $srtpQStartName = "Entered Endpoint SRTP sender outgoing queue";
my $srtpQEndName = "Exited Endpoint SRTP sender outgoing queue";
my $iceStartName = "Exited Endpoint SRTP sender outgoing queue";
my $iceEndName = "Sent over the ICE transport";
my $totalStartName = "Entered RTP receiver incoming queue";
my $totalEndName = "Sent over the ICE transport";

while (<>) {
    if (/Reference time: /) {
        my $prev_time;
        my $receiverQStart = -1;
        my $receiverQEnd = -1;
        my $receiverPStart = -1;
        my $receiverPEnd = -1 ;
        my $terminationNodeStart = -1 ;
        my $terminationNodeEnd = -1;
        my $senderQStart = -1;
        my $senderQEnd = -1;
        my $senderPStart = -1;
        my $senderPEnd = -1 ;
        my $srtpQStart = -1;
        my $srtpQEnd = -1;
        my $iceStart = -1;
        my $iceEnd = -1;
        my $totalStart = -1;
        my $totalEnd = -1;

        while (/; \(([^)]*), PT([0-9.]*)/g) {
            my $name = $1;
            my $time = $2;

            if ($name eq $receiverQStartName) {
                $receiverQStart = $time;
            }
            if ($name eq $receiverQEndName) {
                $receiverQEnd = $time;
            }
            if ($name eq $receiverPStartName) {
                $receiverPStart = $time;
            }
            if ($name eq $receiverPEndName) {
                $receiverPEnd = $time;
            }
            if ($name eq $terminationNodeStartName) {
                $terminationNodeStart = $time;
            }
            if ($name eq $terminationNodeEndName) {
                $terminationNodeEnd = $time;
            }
            if ($name eq $senderQStartName) {
                $senderQStart = $time;
            }
            if ($name eq $senderQEndName) {
                $senderQEnd = $time;
            }
            if ($name eq $senderPStartName) {
                $senderPStart = $time;
            }
            if ($name eq $senderPEndName) {
                $senderPEnd = $time;
            }
            if ($name eq $srtpQStartName) {
                $srtpQStart = $time;
            }
            if ($name eq $srtpQEndName) {
                $srtpQEnd = $time;
            }
            if ($name eq $iceStartName) {
                $iceStart = $time;
            }
            if ($name eq $iceEndName) {
                $iceEnd = $time;
            }
            if ($name eq $totalStartName) {
                $totalStart = $time;
            }
            if ($name eq $totalEndName) {
                $totalEnd = $time;
            }

            #my $delta_time;
            #if (defined $prev_time) {
            #    $delta_time = $time - $prev_time;
            #}
            #else {
            #    $delta_time = $time;
            #}
            #$prev_time = $time;

            #if ($name =~ /Toggleable pcap writer:/) {
            #    $name =~ s/writer: .*/writer/;
            #}
        }
        if ($receiverQStart > -1 && $receiverQEnd > -1) {
            my $name = "1.Receiver Queue";
            my $time = $receiverQEnd - $receiverQStart;

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($time * 1e3);
        }
        if ($receiverPStart > -1 && $receiverPEnd > -1) {
            my $name = "2.Receiver Pipeline";
            my $time = $receiverPEnd - $receiverPStart;

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($time * 1e3);
        }
        if ($terminationNodeStart > -1 && $terminationNodeEnd > -1) {
            my $name = "3.Termination Node";
            my $time = $terminationNodeEnd - $terminationNodeStart;

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($time * 1e3);
        }
        if ($senderQStart > -1 && $senderQEnd > -1) {
            my $name = "4.Sender Queue";
            my $time = $senderQEnd - $senderQStart;

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($time * 1e3);
        }
        if ($senderPStart > -1 && $senderPEnd > -1) {
            my $name = "5.Sender Pipeline";
            my $time = $senderPEnd - $senderPStart;

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($time * 1e3);
        }
        if ($srtpQStart > -1 && $srtpQEnd > -1) {
            my $name = "6.SRTP Queue";
            my $time = $srtpQEnd - $srtpQStart;

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($time * 1e3);
        }
        if ($iceStart > -1 && $iceEnd > -1) {
            my $name = "7.Send over ICE transport";
            my $time = $iceEnd - $iceStart;

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($time * 1e3);
        }
        if ($totalStart > -1 && $totalEnd > -1) {
            my $name = "8.Total";
            my $time = $totalEnd - $totalStart;

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($time * 1e3);
        }
    }
}

for my $name (@stat_names) {
    my $s = $stats{$name};
    printf("%s: min %.3f ms, mean %.3f ms, median %.3f ms, 90%% %.3f ms, 99%% %.3f ms, max %.3f ms\n",
           $name, $s->min(), $s->mean(), $s->median(), scalar($s->percentile(90)), scalar($s->percentile(99)), $s->max());
}
