package io.github.ceratops_code.wobblepad;
interface IController {
    String open(int mode) = 0;
    void send(float x, float y, int keys) = 1;
    void close() = 2;
    String status() = 3;
    String closeBoBoHome() = 4;
    void destroy() = 16777114;
}
