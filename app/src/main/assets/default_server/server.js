const http = require('http');
const fs = require('fs');
const path = require('path');
const port = process.env.PORT || 8080;
const WWW_DIR = '/www';

const server = http.createServer((req, res) => {
    let reqUrl = req.url === '/' ? '/index.html' : req.url;
    let filePath = path.join(WWW_DIR, reqUrl);
    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        return fs.createReadStream(filePath).pipe(res);
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'online', node: process.version, servingFrom: '/sdcard/www' }));
});

server.listen(port, '0.0.0.0', () => {
    console.log(`[DroidNode] Server listening on http://0.0.0.0:${port}`);
});
