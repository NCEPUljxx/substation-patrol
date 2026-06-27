/**
 * 背景粒子动画 - z-index在内容之下
 */
(function () {
  'use strict';
  var canvas = document.createElement('canvas');
  canvas.style.cssText = 'position:fixed;top:0;left:0;z-index:0;pointer-events:none';
  document.body.prepend(canvas);
  var ctx = canvas.getContext('2d');
  var w, h, particles = [];
  var COUNT = 120;

  function resize() {
    w = canvas.width = innerWidth;
    h = canvas.height = innerHeight;
    // Redistribute evenly on resize
    var cols = Math.ceil(Math.sqrt(COUNT * w / h));
    var rows = Math.ceil(COUNT / cols);
    var cw = w / cols, rh = h / rows;
    for (var i = 0; i < COUNT; i++) {
      var col = i % cols, row = Math.floor(i / cols);
      particles[i].x = col * cw + Math.random() * cw;
      particles[i].y = row * rh + Math.random() * rh;
    }
  }

  var cols = Math.ceil(Math.sqrt(COUNT));
  var rows = Math.ceil(COUNT / cols);
  for (var i = 0; i < COUNT; i++) {
    var col = i % cols, row = Math.floor(i / cols);
    particles.push({
      x: col * (1200 / cols) + Math.random() * (1200 / cols),
      y: row * (800 / rows) + Math.random() * (800 / rows),
      r: Math.random() * 2.5 + 1,
      vx: (Math.random() - 0.5) * 0.35,
      vy: (Math.random() - 0.5) * 0.35,
      alpha: Math.random() * 0.45 + 0.15,
      pulse: Math.random() * Math.PI * 2
    });
  }
  resize();
  window.addEventListener('resize', resize);

  function draw(ts) {
    ctx.clearRect(0, 0, w, h);
    for (var i = 0; i < COUNT; i++) {
      var p = particles[i];
      p.x += p.vx; p.y += p.vy;
      if (p.x < 0) p.x = w; if (p.x > w) p.x = 0;
      if (p.y < 0) p.y = h; if (p.y > h) p.y = 0;
      p.pulse += 0.015;
      var a = p.alpha + Math.sin(p.pulse) * 0.15;
      ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(59,130,246,' + Math.max(0.05, a) + ')';
      ctx.fill();
    }
    requestAnimationFrame(draw);
  }
  requestAnimationFrame(draw);
})();
