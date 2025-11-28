// k6 - https://k6.io/
// 実行方法:
//   1) サーバ起動（Spring Boot）
//   2) 別ターミナルで: k6 run k6/allow_smoke.js

import http from 'k6/http';
import { sleep } from 'k6';

export const options = {
  vus: 30,
  duration: '30s',
};

export default function () {
  const key = 'user-' + (__ITER % 100);
  const res = http.post('http://localhost:8080/v1/allow?key=' + key);
  sleep(0.1);
}
