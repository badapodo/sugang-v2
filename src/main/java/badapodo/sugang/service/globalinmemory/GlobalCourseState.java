package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.exception.CapacityExcessException;

final class GlobalCourseState {

    private int remainingCapacity;

    GlobalCourseState(int capacity, int currentCount) {
        this.remainingCapacity = Math.max(0, capacity - currentCount);
    }

    void validateCapacity() {
        if (remainingCapacity <= 0) {
            throw new CapacityExcessException();
        }
    }

    void enroll() {
        remainingCapacity--;
    }
}
