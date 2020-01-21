#!/usr/bin/perl -w

# Parse the bandwidth estimation timeseries log output, and convert
# to JSON in the format expected by chart-bwe-output.html.
# Timeseries logging should be enabled for the relevant subclass of
#  org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator, e.g.
#  timeseries.org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator.level=ALL
#
# Output file should be bwe-output.js.

use strict;
use Data::Dump 'dump';

my %endpoints;

my $check_old_bwe = 0;

sub parse_line($)
{
    my ($line) = @_;

    my %ret;

    if ($line !~ /^{/gc) {
	return undef;
    }

    while (1) {
	if ($line !~ /\G"([A-Za-z0-9_]*)":/gc) {
	    return undef;
	}
	my $field = $1;
	if ($line =~ /\G"((?:[^\\"]|\\.)*)"/gc) {
	    $ret{$field} = $1;
	}
	elsif ($line =~ /\G([^",}]*)/gc) {
	    $ret{$field} = $1;
	}
	if ($line !~ /\G,/gc) {
	    last;
	}
    }

    if ($line !~ /\G}/gc) {
	return undef;
    }

    return \%ret;
}

sub get_field($$)
{
    my ($line, $field) = @_;
    if ($line =~ /"$field":"((?:[^\\"]|\\.)*)"[,}]/) {
	return $1;
    }
    elsif ($line =~ /"$field":([^",}]*)/) {
	return $1;
    }
    return undef;
}

sub get_ep_key($)
{
    my ($line) = @_;

    my $conf_name = $line->{conf_name};
    my $ep_id = $line->{endpoint_id};
    my $conf_time = $line->{conf_creation_time_ms};

    return undef if (!defined($conf_name) || !defined($ep_id) || !defined($conf_time));

    my $ep_key = "$conf_name:$conf_time:$ep_id";
    if (!exists($endpoints{$ep_key})) {
	$endpoints{$ep_key}{info} = [$conf_name, $conf_time, $ep_id];
    }

    return $ep_key;
}

# Determine which of two values is "smaller" modulo a modulus.
# (Assume modulus is an even number.)
sub min_modulo($$$)
{
    my ($a, $b, $modulus) = @_;
    return $a if !defined($b);
    return $b if !defined($a);

    my $delta = ($b - $a) % $modulus;
    $delta -= $modulus if ($delta > $modulus/2);

    return $delta < 0 ? $b : $a;
}

while (<>) {
    my ($line) = parse_line($_);
    next if !defined($line);

    my $key = get_ep_key($line);
    # Use 1e8 since the epoch (March 1973) as the threshold between fake clocks and real clocks.
    my $time = $line->{time} < 1e8 ? $line->{time} / 1e3 : "new Date($line->{time})";
    my $series = $line->{series};
    next if (!defined($key) || !defined($time) || !defined($series));

    if ($check_old_bwe) {
	if ($series eq "in_pkt") {
	    # send_ts_ms is mod 64000, so do all math like that.
	    my $delta = ($line->{recv_ts_ms} - $line->{send_ts_ms}) % 64000;

	    $endpoints{$key}{min_delta} = min_modulo($delta, $endpoints{$key}{min_delta}, 64000);
	    push(@{$endpoints{$key}{trace}}, ["delay", $time, $delta]);
	    $endpoints{columns}{delay} = 1;
	}
	elsif ($series eq "aimd_rtt") {
	    my $entry = ["rtt", $time, $line->{rtt}];
	    push(@{$endpoints{$key}{trace}}, $entry);
	    $endpoints{columns}{rtt} = 1;
	}
	elsif ($series eq "bwe_incoming") {
	    push(@{$endpoints{$key}{trace}}, ["bw", $time, $line->{bitrate_bps}]);
	    $endpoints{columns}{bw} = 1;
	}
    }
    else {
	if ($series eq "bwe_packet_arrival") {
	    my $delta = ($line->{recvTime} - $line->{sendTime});

	    if (!defined($endpoints{$key}{min_delta}) || $delta < $endpoints{$key}{min_delta}) {
		$endpoints{$key}{min_delta} = $delta;
	    }
	    push(@{$endpoints{$key}{trace}}, ["delay", $time, $delta]);
	    $endpoints{$key}{columns}{delay} = 1;
	}
	elsif ($series eq "bwe_rtt") {
	    my $entry = ["rtt", $time, $line->{rtt}];
	    push(@{$endpoints{$key}{trace}}, $entry);
	    $endpoints{$key}{columns}{rtt} = 1;
	}
	elsif ($series eq "bwe_estimate") {
	    push(@{$endpoints{$key}{trace}}, ["bw", $time, $line->{bw}]);
	    $endpoints{$key}{columns}{bw} = 1;
	}
    }
}

sub print_row($$$$$;$) {
    my ($cols, $time, $bw, $rtt, $delay, $skip_comma) = @_;

    my $have_bw = exists($cols->{bw});
    my $have_rtt = exists($cols->{rtt});
    my $have_delay = exists($cols->{delay});

    print "," if (!defined($skip_comma) || $skip_comma);
    print ("[", $time);
    print ",$bw" if ($have_bw);
    print ",$rtt" if ($have_rtt);
    print ",$delay" if ($have_delay);
    print "]\n";
}

print "var charts={";
my $subsequent=0;
foreach my $ep (sort keys %endpoints) {
    my ($conf_name, $conf_time, $ep_id) = @{$endpoints{$ep}{info}};

    my $cols = $endpoints{$ep}{columns};
    my $have_bw = exists($cols->{bw});
    my $have_rtt = exists($cols->{rtt});
    my $have_delay = exists($cols->{delay});

    next if (!$have_delay && !$have_rtt && !$have_bw);

    print "," if ($subsequent); $subsequent = 1;

    my $conf_time_str;
    # See above about 1e8
    if ($conf_time > 1e8) {
	$conf_time_str = "new Date($conf_time).toISOString()";
    }
    else {
	$conf_time_str = "\"$conf_time\"";
    }

    print "\"$ep\":{\"title\":\"Endpoint $ep_id in Conference $conf_name at \" + $conf_time_str,\n";

    print "\"columns\":{";
    print "\"bw\":", $have_bw ? "true," : "false,";
    print "\"rtt\":", $have_rtt ? "true," : "false,";
    print "\"delay\":", $have_delay ? "true},\n" : "false},\n";

    print "\"data\":[";
    print_row($cols, "\"Time\"","\"Bandwidth\"","\"RTT\"","\"Excess One-Way Delay\"", 0);

    foreach my $row (@{$endpoints{$ep}{trace}}) {
	if ($row->[0] eq "delay") {
	    print_row($cols, $row->[1], "null", "null", $row->[2] - $endpoints{$ep}{min_delta});
	}
	elsif ($row->[0] eq "rtt") {
	    print_row($cols, $row->[1], "null", $row->[2], "null");
	}
	elsif ($row->[0] eq "bw") {
	    if ($row->[2] != -1) {
		print_row ($cols, $row->[1], $row->[2], "null", "null");
	    }
	}
    }
    print "]}\n";
}
print "};\n";
