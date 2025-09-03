package com.hmdp.utils;

public interface ILock {

    boolean tryLock(String name, Long timeout);

    void unLock(String name);
}
