#!/usr/bin/env python

import argparse
import pandas as pd
import matplotlib.pyplot as plt
from pandas.plotting import register_matplotlib_converters
register_matplotlib_converters()


def read_json(fname):
    df = pd.read_json(fname, lines=True, convert_dates=['time'], date_unit='ms')
    return df


def show(args):
    df = read_json(args.infile)
    print('series: {}'.format(df['series'].unique()))
    print('conferences: {}'.format(df['conf_name'].unique()))
    print('endpoints: {}'.format(df['endpoint_id'].unique()))


def vp8_inspect(df):
    if args.ssrc:
        df = df[df['rtp.ssrc'] == args.ssrc]
    if args.endpoint:
        df = df[df['endpoint_id'] == args.endpoint]
    if args.conference:
        df = df[df['conf_name'] == args.conference]

    df['time_delta'] = df['time'] - df['time'].shift(1)
    df['rtp.seq_delta'] = df['rtp.seq'] - df['rtp.seq'].shift(1)
    df['vp8.pictureid_delta'] = df['vp8.pictureid'] - df['vp8.pictureid'].shift(1)
    df['vp8.timestamp_delta'] = df['rtp.timestamp'] - df['rtp.timestamp'].shift(1)
    print(df)


def vp8_verify(df):
    # tl0picidx monotonically increases
    # if the timestamp changes, then the pictureid changes
    # if the pictureid changes, then the timestamp changes
    # the timestamp delta needs to be proportional to the pictureid delta
    # there can't be big gaps in the sequence numbers
    # the webrtc pacer outputs packets every 10ms
    pass


def plot_endpoint(df, series, endpoint_id, remote_endpoint_id):
    df = df[df['endpoint_id'] == endpoint_id]
    if remote_endpoint_id:
        df = df[df['remote_endpoint_id'] == remote_endpoint_id]

    fig = plt.figure()
    fig.suptitle('Endpoint: {}'.format(endpoint_id))
    ax_bitrate = fig.subplots(1, sharex=True)
    ax_bitrate.set_xlabel('time')
    ax_bitrate.set_ylabel('bitrate (bps)')

    if 'calculated_rate' in series:
        df_rates = df['series' == 'calculated_rate']

        # make sure we're plotting a single remote endpoint.
        remote_endpoints = df_rates['remote_endpoint_id'].unique()
        if len(remote_endpoints) > 1:
            raise Exception('specify a --remote-endpoint {}'.format(remote_endpoints))

        for i in range(9):
            encoding_quality = str(i)
            if encoding_quality in df_rates.columns:
                ax_bitrate.plot(df_rates['time'],
                                df_rates[encoding_quality],
                                label=encoding_quality)

    if 'sent_padding' in series:
        df_padding = df[df['series'] == 'sent_padding']
        ax_bitrate.plot(
            df_padding['time'],
            df_padding['padding_bps'] + df_padding['total_target_bps'],
            label='target + padding', marker='^',
            markersize = 1, linestyle='None')

    if 'new_bandwidth' in series:
        df_bwe = df[df['series'] == 'new_bandwidth']
        ax_bitrate.step(df_bwe['time'], df_bwe['bitrate_bps'], label='bwe')

    if 'did_update' in series:
        df_update = df[df['series'] == 'did_update']
        ax_bitrate.step(df_update['time'], df_update['total_target_bps'], label='target')
        ax_bitrate.step(df_update['time'], df_update['total_ideal_bps'], label='ideal')

    # todo include rtt and packet loss

    ax_bitrate.legend()


def plot(args):
    df = read_json(args.infile)
    if args.endpoint_id:
        plot_endpoint(df, args.series, args.endpoint_id, args.remote_endpoint_id)
    else:
        # plot all the endpoints!
        for endpoint_id in df['endpoint_id'].unique():
            plot_endpoint(df, args.series, endpoint_id, args.remote_endpoint_id)

    plt.show()


if "__main__" == __name__:
    parser = argparse.ArgumentParser()
    parser.add_argument('infile', type=argparse.FileType('r'))
    subparsers = parser.add_subparsers()
    
    parser_show = subparsers.add_parser('show')
    parser_show.set_defaults(func=show)
    parser_plot = subparsers.add_parser('plot')
    parser_plot.add_argument('--series', nargs='+', choices=[
        'did_update', 'new_bandwidth', 'sent_padding', 'calculated_rate'],
                             default='did_update new_bandwidth sent_padding')
    parser_plot.add_argument('--endpoint-id')
    parser_plot.add_argument('--remote-endpoint-id')
    parser_plot.set_defaults(func=plot)

    args = parser.parse_args()
    args.func(args)
