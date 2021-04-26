package net.enilink.platform.ldp.ldPatch.parse;

public class Slice {
    private int min;
    private int max;

    public Slice min(int min){
        this.min = min;
        return  this;
    }

    public int min(){ return  this.min;}

    public Slice max(int max){
        this.max = max;
        return  this;
    }

    public int max(){ return  this.max;}
}
