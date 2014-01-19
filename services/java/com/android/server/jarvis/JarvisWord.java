package com.android.server.jarvis;

import android.os.Parcel;
import android.os.Parcelable;

public final class JarvisWord implements Parcelable {
    public String slot;
    public String word;
    public String pron;
    public String literal;
    public int priority;

    public static final Parcelable.Creator<JarvisWord> CREATOR = new Parcelable.Creator<JarvisWord>() {
        public JarvisWord createFromParcel(Parcel in) {
            return new JarvisWord(in);
        }

        public JarvisWord[] newArray(int size) {
            return new JarvisWord[size];
        }
    };

    public JarvisWord() {
        literal = "-/-";
        pron = null;
        word = null;
        slot = null;
        priority = 1;
    }

    private JarvisWord(Parcel in) {
        readFromParcel(in);
    }

    public void writeToParcel(Parcel out) {
        out.writeString(slot);
        out.writeString(word);
        out.writeString(pron);
        out.writeString(literal);
        out.writeInt(priority);
    }

    public void readFromParcel(Parcel in) {
        slot = in.readString();
        word = in.readString();
        pron = in.readString();
        literal = in.readString();
        priority = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcel(dest);
    }
}
