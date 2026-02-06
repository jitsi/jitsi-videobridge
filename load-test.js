import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '10s', target: 50 }, // Ramp up to 50 users
        { duration: '20s', target: 50 }, // Stay at 50 users
        { duration: '10s', target: 0 },  // Ramp down to 0
    ],
};

export default function () {
    const healthRes = http.get('http://127.0.0.1:8080/about/health');
    check(healthRes, { 'health status is 200': (r) => r.status === 200 });

    const versionRes = http.get('http://127.0.0.1:8080/about/version');
    check(versionRes, { 'version status is 200': (r) => r.status === 200 });

    sleep(1);
}
