#!/usr/bin/perl -w

use Statistics::Descriptive;
use strict;

my %stats;
my @stat_names;

while (<>) {
    if (/Reference time: /) {
        my $prev_time;
        while (/; \(([^)]*), PT([0-9.]*)/g) {
            my $name = $1;
            my $time = $2;

            my $delta_time;
            if (defined $prev_time) {
                $delta_time = $time - $prev_time;
            }
            else {
                $delta_time = $time;
            }
            $prev_time = $time;

            if ($name =~ /Toggleable pcap writer:/) {
                $name =~ s/writer: .*/writer/;
            }

            if (!defined ($stats{$name})) {
                $stats{$name} = Statistics::Descriptive::Full->new();
                push(@stat_names, $name);
            }
            $stats{$name}->add_data($delta_time * 1e3);
        }
    }
}

for my $name (@stat_names) {
    my $s = $stats{$name};
    printf("%s: min %.3f ms, mean %.3f ms, median %.3f ms, 90%% %.3f ms, 99%% %.3f ms, max %.3f ms\n",
           $name, $s->min(), $s->mean(), $s->median(), scalar($s->percentile(90)), scalar($s->percentile(99)), $s->max());
}
