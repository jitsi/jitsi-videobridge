#!/usr/bin/env python

import argparse
import pandas as pd
import sys

pd.set_option('display.max_columns', None)
pd.set_option('display.max_rows', None)
pd.set_option('display.width', None)


def read_json(fname, convert_dates=['time']):
    df = pd.read_json(fname, lines=True, convert_dates=convert_dates, date_unit='ms')
    return df


def show(args):
    df = read_json(args.infile, convert_dates=False)
    print('series: {}'.format(df['series'].unique()))
    print('conferences: {}'.format(df['conf_name'].unique()))
    print('endpoints: {}'.format(df['endpoint_id'].unique()))
    print('ssrcs: {}'.format(df['rtp.ssrc'].unique()))


def vp8_inspect(args):
    df = read_json(args.infile, convert_dates=False)
    df = df[df['series'] == 'rtp_vp8_rewrite']
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


def probing_show(args):
    df = read_json(args.infile, convert_dates=False)
    df = df[df['series'] == 'out_padding']
    print('endpoints: {}'.format(df['endpoint_id'].unique()))


def probing_plot(args):
    df = read_json(args.infile)
    df = df[df['series'] == 'out_padding']

    if args.endpoint:
        df = df[df['endpoint_id'] == args.endpoint]
    else:
        endpoints = df['endpoint_id'].unique()
        if len(endpoints) > 1:
            raise Exception('Which endpoint to plot? {}'.format(endpoints))

    import matplotlib.pyplot as plt
    df.plot('time', ['padding_bps',
                     'bwe_bps',
                     'total_ideal_bps',
                     #'needed_bps',
                     #'max_padding_bps',
                     'total_target_bps'])
    plt.show()


def setup_probing_subparser(parser):
    subparsers = parser.add_subparsers()

    parser_show = subparsers.add_parser('show')
    parser_show.set_defaults(func=probing_show)
    
    parser_plot = subparsers.add_parser('plot')
    parser_plot.add_argument('--endpoint')
    parser_plot.set_defaults(func=probing_plot)


def setup_vp8_subparser(parser):
    subparsers = parser.add_subparsers()
    
    parser_inspect = subparsers.add_parser('inspect')
    parser_inspect.add_argument('--conference')
    parser_inspect.add_argument('--endpoint')
    parser_inspect.add_argument('--ssrc', type=int)
    parser_inspect.set_defaults(func=vp8_inspect)


def setup_bwe_subparser(parser):
    subparsers = parser.add_subparsers()

    parser_plot = subparsers.add_parser('plot')
    parser_plot.add_argument('--endpoint')
    parser_plot.set_defaults(func=bwe_plot)


def rates_plot(args):
    df = read_json(args.infile)
    df = df[df['series'] == 'rates']
    for i in range(9):
        if not str(i) in df.columns:
            df[str(i)] = 0

    if args.endpoint:
        df = df[df['endpoint_id'] == args.endpoint]
    else:
        endpoints = df['endpoint_id'].unique()
        if len(endpoints) > 1:
            raise Exception('Which endpoint to plot? {}'.format(endpoints))

    if args.remote_endpoint:
        df = df[df['remote_endpoint_id'] == args.remote_endpoint]
    else:
        remote_endpoints = df['remote_endpoint_id'].unique()
        if len(remote_endpoints) > 1:
            raise Exception('Which remote endpoint to plot? {}'.format(remote_endpoints))

    import matplotlib.pyplot as plt
    df.plot('time', ['0', '1', '2', '3', '4', '5', '6', '7', '8'], grid=True)
    plt.show()


def setup_rates_subparser(parser):
    subparsers = parser.add_subparsers()

    parser_plot = subparsers.add_parser('plot')
    parser_plot.add_argument('--endpoint')
    parser_plot.add_argument('--remote-endpoint')
    parser_plot.set_defaults(func=rates_plot)


def bwe_plot(args):
    df = read_json(args.infile)
    df = df[df['series'] == 'bwe_incoming']
    
    if args.endpoint:
        df = df[df['endpoint_id'] == args.endpoint]
    else:
        endpoints = df['endpoint_id'].unique()
        if len(endpoints) > 1:
            raise Exception('Which endpoint to plot? {}'.format(endpoints))

    import matplotlib.pyplot as plt
    df.plot('time', ['bitrate_bps'], style='.-')
    plt.show()


if "__main__" == __name__:
    parser = argparse.ArgumentParser()
    parser.add_argument('infile', type=argparse.FileType('r'))
    subparsers = parser.add_subparsers()

    setup_vp8_subparser(subparsers.add_parser('vp8'))
    setup_probing_subparser(subparsers.add_parser('probing'))
    setup_bwe_subparser(subparsers.add_parser('bwe'))
    setup_rates_subparser(subparsers.add_parser('rates'))
    
    parser_show = subparsers.add_parser('show')
    parser_show.set_defaults(func=show)

    args = parser.parse_args()
    args.func(args)
