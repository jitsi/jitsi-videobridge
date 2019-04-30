#!/usr/bin/env python

import pandas as pd
import sys

pd.set_option('display.max_columns', None)
pd.set_option('display.max_rows', None)
pd.set_option('display.width', None)

def augment(df):
    df['date_delta'] = df['date'] - df['date'].shift(1)
    df['rtp.seq_delta'] = df['rtp.seq'] - df['rtp.seq'].shift(1)
    df['vp8.pictureid_delta'] = df['vp8.pictureid'] - df['vp8.pictureid'].shift(1)
    df['vp8.timestamp_delta'] = df['rtp.timestamp'] - df['rtp.timestamp'].shift(1)
    return df

def verify(df):
    # tl0picidx monotonically increases
    # if the timestamp changes, then the pictureid changes
    # if the pictureid changes, then the timestamp changes
    # the timestamp delta needs to be proportional to the pictureid delta
    # there can't be big gaps in the sequence numbers
    # the webrtc pacer outputs packets every 10ms
    pass

def main():
    df = pd.read_json(sys.stdin, lines=True, convert_dates=False, date_unit='ms')
    df = df[df['series'] == 'rtp_vp8_rewrite']
    df = df.drop(columns=['series'])
    if len(sys.argv) == 1:
        print(df.ssrc.unique())
    else:
        ssrc = sys.argv[1]
        print(augment(df[df['rtp.ssrc'] == int(ssrc)]))

if "__main__" == __name__:
    main()
