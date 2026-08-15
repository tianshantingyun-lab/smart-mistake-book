import "@testing-library/jest-dom/vitest";
import "fake-indexeddb/auto";
import { cleanup, configure } from "@testing-library/react";
import { afterEach } from "vitest";

configure({ asyncUtilTimeout: 3000 });

afterEach(() => {
  cleanup();
});

function createMemoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    clear() {
      values.clear();
    },
    getItem(key) {
      return values.get(key) ?? null;
    },
    key(index) {
      return [...values.keys()][index] ?? null;
    },
    removeItem(key) {
      values.delete(key);
    },
    setItem(key, value) {
      values.set(key, String(value));
    },
  };
}

const local = createMemoryStorage();
const session = createMemoryStorage();

Object.defineProperty(globalThis, "localStorage", {
  configurable: true,
  value: local,
});

Object.defineProperty(globalThis, "sessionStorage", {
  configurable: true,
  value: session,
});

Object.defineProperty(window, "localStorage", {
  configurable: true,
  value: local,
});

Object.defineProperty(window, "sessionStorage", {
  configurable: true,
  value: session,
});

Object.defineProperty(window, "scrollTo", {
  configurable: true,
  value: () => undefined,
  writable: true,
});

let testWebLockQueue: Promise<void> = Promise.resolve();
const testLockManager = {
  request<T>(
    name: string,
    callback: (lock: Lock | null) => Promise<T> | T,
  ): Promise<T> {
    const run = () =>
      Promise.resolve(callback({ name, mode: "exclusive" } as Lock));
    const result = testWebLockQueue.then(run, run);
    testWebLockQueue = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  },
};

Object.defineProperty(window.navigator, "locks", {
  configurable: true,
  value: testLockManager,
});
