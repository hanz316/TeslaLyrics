package com.netease.cloudmusic.aidl;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Wire-compatible shadow of NetEase 9.4.70 MediaSessionCallbackParam.
 * The receiving NetEase process loads its own class with the same FQCN.
 * Parcel field order matches the installed build: String, long, Parcelable.
 */
public class MediaSessionCallbackParam implements Parcelable {
    private String arg1;
    private long arg2;
    private Parcelable extraData;

    public MediaSessionCallbackParam() {}

    protected MediaSessionCallbackParam(Parcel in) {
        arg1 = in.readString();
        arg2 = in.readLong();
        extraData = in.readParcelable(MediaSessionCallbackParam.class.getClassLoader());
    }

    public void setArg1(String value) { arg1 = value; }
    public void setArg2(long value) { arg2 = value; }
    public void setExtraData(Parcelable value) { extraData = value; }
    public String getArg1() { return arg1; }
    public long getArg2() { return arg2; }
    public Parcelable getExtraData() { return extraData; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(arg1);
        dest.writeLong(arg2);
        dest.writeParcelable(extraData, flags);
    }

    public static final Creator<MediaSessionCallbackParam> CREATOR = new Creator<MediaSessionCallbackParam>() {
        @Override public MediaSessionCallbackParam createFromParcel(Parcel in) {
            return new MediaSessionCallbackParam(in);
        }
        @Override public MediaSessionCallbackParam[] newArray(int size) {
            return new MediaSessionCallbackParam[size];
        }
    };
}
