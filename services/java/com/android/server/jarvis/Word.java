package com.android.server.jarvis;

public class Word {
    private String mWord, mLiteral, mPron;
    public final char first;
    
    public Word(String s) {
        mWord = s;
        first = s.charAt(0);
    }
    
    public String getWord() {
        return mWord;
    }
    
    public String getLiteral() {
        return mLiteral;
    }
    
    public String getPron() {
        return mPron;
    }
    
    public void setLiteral(String l) {
        mLiteral = l;
    }
    
    public void setPron(String p) {
        mPron = p;
    }
    
    public String toApiString() {
        //Stored as: [slot];[word];[pron];[literal];[priority]
        String s = ";";
        if(mWord != null) s += mWord;
        s += ";";
        if(mPron != null) s += mPron;
        s += ";";
        if(mLiteral != null) s += mLiteral;
        s += ";1";
        return s;
    }
}