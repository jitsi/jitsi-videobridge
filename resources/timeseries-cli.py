#!/usr/bin/env python

import argparse
import pandas as pd
import matplotlib.pyplot as plt
from pandas.plotting import register_matplotlib_converters
register_matplotlib_converters()


def read_json(fname):
    df = pd.read_json(fname, lines=True, convert_dates=['time'], date_unit='ms')
    df['conference_id'] = df['conf_name'] + df['conf_creation_time_ms'].astype(str)
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

    df_series = df['series'].unique()
    if 'sent_padding' in series and 'sent_padding' in df_series:
        df_padding = df[df['series'] == 'sent_padding']
        ax_bitrate.plot(
            df_padding['time'],
            df_padding['padding_bps'] + df_padding['total_target_bps'],
            label='target + padding',
            marker='^',
            markersize=4,
            linestyle='None')

    if 'new_bwe' in series and 'new_bwe' in df_series:
        df_bwe = df[df['series'] == 'new_bwe']
        ax_bitrate.plot(df_bwe['time'],
                        df_bwe['bitrate_bps'],
                        label='estimate',
                        drawstyle='steps-post')

    if 'did_update' in series and 'did_update' in df_series:
        df_update = df[df['series'] == 'did_update']
        ax_bitrate.plot(df_update['time'],
                        df_update['total_target_bps'],
                        label='target',
                        drawstyle='steps-post')
        ax_bitrate.plot(df_update['time'],
                        df_update['total_ideal_bps'],
                        label='ideal',
                        drawstyle='steps-post')

    if 'in_pkt' in series and 'in_pkt' in df_series:
        df_pkt = df[df['series'] == 'in_pkt']

        if len(df_pkt['rbe_id'].unique()) is not 1:
            raise Exception('There cannot be multiple remote bitrate estimators')

        df_sz = pd.DataFrame(
            {'bits': df_pkt['pkt_sz_bytes'] * 8, 'time': df_pkt['time']})

        df_rate = df_sz.resample('1S', on='time').sum()
        ax_bitrate.plot(df_rate.index, df_rate['bits'], label='sent')

    # todo include rtt and packet loss

    ax_bitrate.legend()


def plot(args):
    df = read_json(args.infile)

    endpoint_ids = [args.endpoint_id] if args.endpoint_id else df['endpoint_id'].unique()

    for endpoint_id in endpoint_ids:
        plot_endpoint(df, args.series, endpoint_id, args.remote_endpoint_id)

    plt.show()


def check_endpoint(df, endpoint_id):

    # if a property is violated (for example property 3):
    #     zgrep 7d2b7ecf data/4/series.json.gz| grep did_update | jq '. | select(.bwe_bps >= .total_ideal_bps) | select(.total_target_idx < .total_ideal_idx)' | less -S

    df_update = df[df['series'] == 'did_update']
    if not len(df_update):
        return

    # property 1: check if ideal <= estimate => target == ideal
    mask_1 = df_update['bwe_bps'] >= df_update['total_ideal_bps']
    mask_2 = df_update['total_target_idx'] < df_update['total_ideal_idx']
    mask_3 = df_update['total_ideal_bps'] != 0
    mask_4 = df_update['total_target_bps'] != 0
    assert not len(df_update[mask_1 & mask_2 & mask_3 & mask_4]), '{} is not sending as much as it should.'.format(endpoint_id)

    # property 2: check that the target never exceeds the ideal
    mask_5 = (df_update['total_ideal_bps'] - df_update['total_target_bps']) < 0
    assert not len(df_update[mask_5]), '{} has a target bitrate that exceeds the ideal bitrate.'.format(endpoint_id)

    # property 3: make sure that we are calculating bitrate for encodings
    # disabled for now as it's a legitimate case, being video muted, for example
    #mask_6 = df_update['total_ideal_bps'] > 0
    #assert len(df_update[mask_6]), '{} never computed an ideal bitrate.'.format(endpoint_id)

    #mask_7 = df_update['total_target_bps'] > 0
    #assert len(df_update[mask_7]), '{} never computed a target bitrate.'.format(endpoint_id)

    # property 4: check that the target never exceeds the estimate
    # disabled for now as can happen and still be correct because we never suspend the on-stage participant
    # mask_8 = (df_update['bwe_bps'] - df_update['total_target_bps']) < 0
    # assert not len(df_update[mask_8]), '{} has a target bitrate that exceeds the bandwidth estimation.'.format(endpoint_id)


def check_conference(df, conference_id):
    endpoint_ids = [args.endpoint_id] if args.endpoint_id else df['endpoint_id'].unique()

    for endpoint_id in endpoint_ids:
        check_endpoint(df[df['endpoint_id'] == endpoint_id], endpoint_id)


def check(args):
    df = read_json(args.infile)
    conference_ids = [args.conference_id] if args.conference_id else df['conference_id'].unique()

    for conference_id in conference_ids:
        check_conference(df[df['conference_id'] == conference_id], conference_id)


if "__main__" == __name__:
    parser = argparse.ArgumentParser()
    parser.add_argument('infile', type=argparse.FileType('r'))
    subparsers = parser.add_subparsers()
    
    parser_show = subparsers.add_parser('show')
    parser_show.set_defaults(func=show)

    parser_plot = subparsers.add_parser('plot')
    parser_plot.add_argument(
        '--series', nargs='+', choices=[
        'did_update', 'new_bwe', 'sent_padding', 'calculated_rate'],
        default='did_update new_bwe sent_padding in_pkt')
    parser_plot.add_argument('--endpoint-id')
    parser_plot.add_argument('--remote-endpoint-id')
    parser_plot.set_defaults(func=plot)

    parser_check = subparsers.add_parser('check')
    parser_check.add_argument('--endpoint-id')
    parser_check.add_argument('--conference-id')
    parser_check.set_defaults(func=check)

    args = parser.parse_args()
    args.func(args)
