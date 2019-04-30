#!/usr/bin/env python

import argparse
import pandas as pd
import sys

pd.set_option('display.max_columns', None)
pd.set_option('display.max_rows', None)
pd.set_option('display.width', None)

def read_json(fname):
    return pd.read_json(fname, lines=True, convert_dates=False, date_unit='ms')

def inspect(args):
    df = read_json(args.infile)
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

def show(args):
    df = read_json(args.infile)
    print('conferences: {}'.format(df['conf_name'].unique()))
    print('endpoints: {}'.format(df['endpoint_id'].unique()))
    print('ssrcs: {}'.format(df['rtp.ssrc'].unique()))

def verify(df):
    # tl0picidx monotonically increases
    # if the timestamp changes, then the pictureid changes
    # if the pictureid changes, then the timestamp changes
    # the timestamp delta needs to be proportional to the pictureid delta
    # there can't be big gaps in the sequence numbers
    # the webrtc pacer outputs packets every 10ms
    pass

if "__main__" == __name__:
    parser = argparse.ArgumentParser()
    parser.add_argument('infile', type=argparse.FileType('r'))
    subparsers = parser.add_subparsers()

    parser_inspect = subparsers.add_parser('inspect')
    parser_inspect.add_argument('--conference')
    parser_inspect.add_argument('--endpoint')
    parser_inspect.add_argument('--ssrc', type=int)
    parser_inspect.set_defaults(func=inspect)

    parser_show = subparsers.add_parser('show')
    parser_show.set_defaults(func=show)

    args = parser.parse_args()
    args.func(args)
