package com.example.charge_to_an_account;

/** 一笔支出记录 */
public class Record {
    public long id;
    public double amount;
    public String category;
    public String note;
    public long createdAt; // epoch millis

    public Record(long id, double amount, String category, String note, long createdAt) {
        this.id = id;
        this.amount = amount;
        this.category = category;
        this.note = note;
        this.createdAt = createdAt;
    }
}
