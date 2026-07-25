// Cellar hero — a phone with a server rack inside, hanging bulb, basement fog.
// Self-contained: three.js is vendored next to this file. Degrades to the CSS
// background when WebGL is unavailable, and to a single still frame under
// prefers-reduced-motion (re-rendered on resize so rotation never blanks it).
import * as THREE from './three.module.js';

const canvas = document.getElementById('scene');
const params = new URLSearchParams(location.search);
// ?still renders one frame; ?pdb preserves the buffer — both for
// screenshot rigs (headless software-GL loses contexts under animation)
const still = params.has('still');
const reduced = still || matchMedia('(prefers-reduced-motion: reduce)').matches;

function makeRenderer() {
  try {
    return new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: params.has('pdb') });
  } catch {
    return null;
  }
}

const renderer = makeRenderer();
if (!renderer) {
  canvas.remove(); // the .hero CSS background is the fallback scene
  console.warn('cellar: WebGL unavailable — using the CSS fallback hero');
} else {
  boot(renderer);
}

function boot(renderer) {
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));

  // if the context dies and doesn't come back, drop to the CSS fallback
  // rather than leaving a dead (possibly white) canvas over the hero
  let lostAt = 0;
  canvas.addEventListener('webglcontextlost', () => {
    lostAt = Date.now();
    setTimeout(() => { if (lostAt) canvas.style.display = 'none'; }, 3000);
  });
  canvas.addEventListener('webglcontextrestored', () => {
    lostAt = 0;
    canvas.style.display = '';
  });

  const scene = new THREE.Scene();
  // opaque background matching the fog: fog blends seamlessly, and we never
  // depend on canvas alpha compositing (which some browsers get wrong)
  scene.background = new THREE.Color(0x07090d);
  scene.fog = new THREE.FogExp2(0x07090d, 0.055);

  const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 60);
  camera.position.set(0, 1.35, 8.2);
  const lookAt = new THREE.Vector3(0, 1.35, 0);

  // ---------- lights: one warm bulb over a dark floor ----------
  scene.add(new THREE.HemisphereLight(0x2a3346, 0x05070a, 0.55));

  const bulb = new THREE.PointLight(0xffb454, 42, 26, 2);
  bulb.position.set(0, 5.4, 1.6);
  scene.add(bulb);

  const bulbBase = bulb.intensity;
  const bulbMesh = new THREE.Mesh(
    new THREE.SphereGeometry(0.07, 12, 12),
    new THREE.MeshBasicMaterial({ color: 0xffd9a0 }),
  );
  bulbMesh.position.copy(bulb.position);
  scene.add(bulbMesh);

  const cord = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(0, 9, 1.6), bulb.position.clone(),
    ]),
    new THREE.LineBasicMaterial({ color: 0x2a3346 }),
  );
  scene.add(cord);

  const rim = new THREE.DirectionalLight(0x46d47e, 0.6);
  rim.position.set(-4, 2, -6);
  scene.add(rim);

  // ---------- floor ----------
  const grid = new THREE.GridHelper(50, 50, 0x232c3d, 0x121826);
  grid.position.y = -0.55;
  scene.add(grid);

  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(60, 60),
    new THREE.MeshStandardMaterial({ color: 0x07090d, roughness: 0.95 }),
  );
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = -0.57;
  scene.add(floor);

  // ---------- the phone, glass slab with a rack inside ----------
  const rig = new THREE.Group();
  rig.position.y = 1.55;
  scene.add(rig);

  const PW = 1.95, PH = 4.05, PD = 0.26;

  const body = new THREE.Mesh(
    new THREE.BoxGeometry(PW, PH, PD),
    new THREE.MeshStandardMaterial({
      color: 0x0c1017, metalness: 0.65, roughness: 0.3,
      transparent: true, opacity: 0.38, depthWrite: false,
    }),
  );
  rig.add(body);

  const frame = new THREE.LineSegments(
    new THREE.EdgesGeometry(new THREE.BoxGeometry(PW, PH, PD)),
    new THREE.LineBasicMaterial({ color: 0xffb454, transparent: true, opacity: 0.85 }),
  );
  rig.add(frame);

  // camera punch-hole, a phone tell
  const dot = new THREE.Mesh(
    new THREE.CircleGeometry(0.035, 16),
    new THREE.MeshBasicMaterial({ color: 0x1b2230 }),
  );
  dot.position.set(0, PH / 2 - 0.22, PD / 2 + 0.002);
  rig.add(dot);

  // rack units inside
  const leds = [];
  const unitGeo = new THREE.BoxGeometry(PW * 0.72, 0.4, 0.13);
  const unitMat = new THREE.MeshStandardMaterial({ color: 0x151c28, metalness: 0.55, roughness: 0.45 });
  const slotGeo = new THREE.PlaneGeometry(PW * 0.5, 0.028);
  const slotMat = new THREE.MeshBasicMaterial({ color: 0x0a2818, transparent: true, opacity: 0.9 });
  const ledGeo = new THREE.SphereGeometry(0.026, 8, 8);

  for (let i = 0; i < 6; i++) {
    const unit = new THREE.Mesh(unitGeo, unitMat);
    const y = 1.45 - i * 0.58;
    unit.position.set(0, y, 0);
    rig.add(unit);

    const slot = new THREE.Mesh(slotGeo, slotMat);
    slot.position.set(-PW * 0.06, y - 0.09, 0.075);
    rig.add(slot);

    for (let j = 0; j < 3; j++) {
      const led = new THREE.Mesh(
        ledGeo,
        new THREE.MeshBasicMaterial({
          color: (i + j) % 4 === 0 ? 0xffb454 : 0x46d47e,
          transparent: true,
        }),
      );
      led.position.set(PW * 0.36 - j * 0.16, y + 0.08, 0.075);
      led.userData = { phase: Math.random() * Math.PI * 2, speed: 1.5 + Math.random() * 4 };
      leds.push(led);
      rig.add(led);
    }
  }

  // green heart of the machine
  const core = new THREE.PointLight(0x46d47e, 5, 6, 2);
  core.position.set(0, 1.4, 0.4);
  scene.add(core);

  // ---------- rising data motes ----------
  const N = 320;
  const pos = new Float32Array(N * 3);
  const vel = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    pos[i * 3] = (Math.random() - 0.5) * 14;
    pos[i * 3 + 1] = Math.random() * 7 - 0.5;
    pos[i * 3 + 2] = (Math.random() - 0.5) * 10;
    vel[i] = 0.15 + Math.random() * 0.5;
  }
  const motesGeo = new THREE.BufferGeometry();
  motesGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const motes = new THREE.Points(motesGeo, new THREE.PointsMaterial({
    color: 0xffb454, size: 0.035, transparent: true, opacity: 0.5,
    blending: THREE.AdditiveBlending, depthWrite: false,
  }));
  scene.add(motes);

  // ---------- interaction ----------
  const mouse = { x: 0, y: 0 };
  addEventListener('pointermove', e => {
    mouse.x = (e.clientX / innerWidth) * 2 - 1;
    mouse.y = (e.clientY / innerHeight) * 2 - 1;
  }, { passive: true });

  function resize() {
    const w = canvas.clientWidth || innerWidth;
    const h = canvas.clientHeight || innerHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    // keep the whole phone in frame on narrow screens
    camera.position.z = camera.aspect < 0.8 ? 10.5 : 8.2;
    camera.updateProjectionMatrix();
    if (reduced) render(); // still mode: setSize clears the frame — repaint it
  }
  addEventListener('resize', resize);

  // ---------- loop ----------
  const clock = new THREE.Clock();
  let flickerAt = 4;

  function render() {
    camera.lookAt(lookAt);
    renderer.render(scene, camera);
  }

  function frameOnce() {
    const t = clock.getElapsedTime();

    rig.rotation.y = t * 0.28;
    rig.position.y = 1.55 + Math.sin(t * 0.7) * 0.05;

    for (const led of leds) {
      const s = Math.sin(t * led.userData.speed + led.userData.phase);
      led.material.opacity = s > -0.1 ? 1 : 0.15;
    }

    // an old bulb flickers, rarely and briefly
    if (t > flickerAt) {
      bulb.intensity = bulbBase * (0.55 + Math.random() * 0.45);
      if (t > flickerAt + 0.35) { bulb.intensity = bulbBase; flickerAt = t + 6 + Math.random() * 9; }
    }

    const p = motesGeo.attributes.position.array;
    for (let i = 0; i < N; i++) {
      p[i * 3 + 1] += vel[i] * 0.016;
      if (p[i * 3 + 1] > 7) p[i * 3 + 1] = -0.5;
    }
    motesGeo.attributes.position.needsUpdate = true;

    camera.position.x += (mouse.x * 0.9 - camera.position.x) * 0.04;
    camera.position.y += (1.35 - mouse.y * 0.5 - camera.position.y) * 0.04;

    render();
  }

  resize();

  if (reduced) {
    rig.rotation.y = 0.5;
    render();
  } else {
    let raf;
    const loop = () => { frameOnce(); raf = requestAnimationFrame(loop); };
    loop();
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) cancelAnimationFrame(raf);
      else loop();
    });
  }
}
