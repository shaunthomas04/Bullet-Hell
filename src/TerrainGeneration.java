//javac -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainGeneration.java
//java -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainGeneration

import java.awt.image.BufferedImage; //ST
import java.io.BufferedReader; //ST
import java.io.File;
import java.io.FileReader; //ST
import java.io.IOException; //ST
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sound.sampled.AudioInputStream; // AV: audio import for loading wav files
import javax.sound.sampled.AudioSystem; // AV: audio import for loading wav files
import javax.sound.sampled.Clip; // AV: audio import for simple sound playback
import javax.sound.sampled.FloatControl; // AV: volume control for louder impact sound

import javax.imageio.ImageIO; //ST

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

public class TerrainGeneration { //ST
    private long window;
    private int width = 1920;
    private int height = 1080;
    private Terrain terrain; //ST
    private BulletSystem bulletSystem; //ST: bullet rain
    private SoundPlayer soundPlayer; // AV: handles bullet impact sound

    public static void main(String[] args) {
        new TerrainGeneration().run();
    }

    public void run() {
        init();
        loop();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private void init() { //ST
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 4);
        window = GLFW.glfwCreateWindow(width, height, "Bullet Hell", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        GL11.glEnable(GL13.GL_MULTISAMPLE);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        // ST: red background so bullets stand out against the scene
        GL11.glClearColor(0.67f, 0.30f, 0.30f, 1.0f);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        setPerspectiveProjection(60.0f, (float) width / height, 0.1f, 2000.0f); // ST: wider FOV + far plane
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        GL11.glEnable(GL11.GL_TEXTURE_2D); //ST

        // ST: enable lighting so bullets have depth and shading
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);

        // ST: bright overhead sun light
        FloatBuffer lightPos = BufferUtils.createFloatBuffer(4);
        lightPos.put(new float[]{0.5f, 1.0f, 0.5f, 0.0f}).flip(); // directional
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPos);

        FloatBuffer lightDiff = BufferUtils.createFloatBuffer(4);
        lightDiff.put(new float[]{1.0f, 0.95f, 0.85f, 1.0f}).flip(); // warm sun colour
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, lightDiff);

        FloatBuffer lightAmb = BufferUtils.createFloatBuffer(4);
        lightAmb.put(new float[]{0.35f, 0.35f, 0.4f, 1.0f}).flip(); // cool ambient fill
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_AMBIENT, lightAmb);

        // ST: smooth shading and normalise after glScale
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_NORMALIZE);

        terrain = new Terrain("fractal_terrain.obj", "terrain.png"); //ST: load OBJ + texture
//        terrain = new Terrain("fractal_terrain.obj", "black-stone.png"); //ST: load OBJ + texture


        // AV: load impact sound from the project root
        soundPlayer = new SoundPlayer();
        soundPlayer.load("impact.wav");

        // ST: init bullet system using terrain bounds so bullets spawn above the terrain
        bulletSystem = new BulletSystem(
                terrain.getMinX(), terrain.getMaxX(),
                terrain.getMinZ(), terrain.getMaxZ(),
                terrain,
                soundPlayer // AV: pass sound player so bullet impacts can trigger audio
        );
    }

    private void loop() { //ST
        long lastTime = System.nanoTime();

        while (!GLFW.glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            float dt = (now - lastTime) / 1_000_000_000.0f; // seconds
            lastTime = now;

            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glLoadIdentity();

            // ST: flat horizontal view — camera sits at terrain level looking straight
            // at the terrain face-on; bullets fall from above into the scene
            float centerX = terrain.getCenterX();
            float maxDim  = terrain.getMaxDimension();

            // no X rotation = perfectly level camera
            // pull back far enough to see the full terrain width, sit at mid-height
            GL11.glTranslatef(-centerX, -maxDim * 0.24f, -maxDim * 1.4f);

            terrain.render(); //ST

            bulletSystem.update(dt); //ST: update bullet positions
            bulletSystem.render();   //ST: draw all bullets

            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    private void setPerspectiveProjection(float fov, float aspect, float zNear, float zFar) { //book
        float ymax = (float)(zNear * Math.tan(Math.toRadians(fov / 2.0)));
        float xmax = ymax * aspect;

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glFrustum(-xmax, xmax, -ymax, ymax, zNear, zFar);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}

// ST: manages spawning and updating a rain of small and large bullets
class BulletSystem {
    // AV: increased active bullet cap so the scene stays filled longer
    private static final int   MAX_BULLETS  = 160;

    // AV: faster spawn rate for more consistent long-running animation
    private static final float SPAWN_RATE   = 18.0f;

    private static final float FALL_SPEED   = 18.0f;
    private static final float SPAWN_HEIGHT = 200.0f; // AV: spawn higher so bullets have more time to fall and bounce across the terrain

    // AV: a little lower failsafe kill height
    private static final float KILL_Y       = -10.0f;

    // ST: scale large enough to be clearly visible in the scene
    private static final float SMALL_SCALE = 1.2f;
    private static final float LARGE_SCALE = 5.2f;

    // AV: tuned bounce settings for smoother terrain collisions
    private static final float GRAVITY             = 25.0f;
    private static final float BOUNCE_DAMPEN       = 0.35f;
    private static final float LATERAL_DAMPEN      = 0.82f;
    private static final float MIN_VERTICAL_BOUNCE = 1.2f;
    private static final int   MAX_BOUNCES         = 4;
    private static final float SURFACE_OFFSET      = 0.08f;
    private static final float REST_TIME           = 1.0f;
    private static final float MIN_IMPACT_SOUND_SPEED = 4.0f; // AV: only play sound for stronger hits

    private final List<Bullet> bullets = new ArrayList<>();
    private final Random rng = new Random();
    private float spawnAccumulator = 0.0f;

    private final float minX, maxX, minZ, maxZ;

    private final OBJMesh smallMesh;
    private final OBJMesh largeMesh;

    // AV: texture ids for the bullet PNG files
    private final int smallBulletTexture;
    private final int largeBulletTexture;

    private final Terrain terrain; // NEW: reference to terrain for height sampling
    private final SoundPlayer soundPlayer; // AV: audio player for impact sounds

    public BulletSystem(float minX, float maxX, float minZ, float maxZ, Terrain terrain, SoundPlayer soundPlayer) { // AV: sound player param added
        this.minX = minX; this.maxX = maxX;
        this.minZ = minZ; this.maxZ = maxZ;
        this.terrain = terrain; // NEW
        this.soundPlayer = soundPlayer; // AV

        smallMesh = new OBJMesh("small_bullet.obj");
        largeMesh = new OBJMesh("large_bullet.obj");

        // AV: load bullet textures so each bullet mesh can use its own PNG image
        //https://pixabay.com/illustrations/grunge-golden-gold-backround-6097785/
        smallBulletTexture = TextureLoader.load("small_bullet.png");

        //https://pixabay.com/vectors/gold-square-label-metallic-fund-1412245/
        largeBulletTexture = TextureLoader.load("large_bullet.png");
    }

    public void update(float dt) {
        spawnAccumulator += dt * SPAWN_RATE;
        while (spawnAccumulator >= 1.0f && bullets.size() < MAX_BULLETS) {
            spawnAccumulator -= 1.0f;
            spawnBullet();
        }

        bullets.removeIf(b -> {
            // AV: resting bullets now expire after a short delay so spawning never permanently stops
            if (b.resting) {
                b.restTimer -= dt;
                return b.restTimer <= 0.0f;
            }

            // AV: save previous position so bounce only happens when crossing into the terrain
            float prevY = b.y;

            // AV: apply gravity to the bullet each frame
            b.vy -= GRAVITY * dt;

            // AV: move bullet using current velocity
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.z += b.vz * dt;

            // AV: clamp bullet to terrain bounds so sampling always stays valid
            b.x = Math.max(minX, Math.min(maxX, b.x));
            b.z = Math.max(minZ, Math.min(maxZ, b.z));

            // AV: sample terrain height at the bullet's current position
            float terrainY = terrain.getHeightAt(b.x, b.z);

            // AV: only bounce when bullet actually crosses downward through the terrain surface
            if (prevY > terrainY && b.y <= terrainY && b.vy < 0.0f) {
                // AV: play sound on stronger terrain impacts
                if (Math.abs(b.vy) > MIN_IMPACT_SOUND_SPEED) {
                    soundPlayer.play();
                }

                float[] normal = terrain.getNormalAt(b.x, b.z);

                // AV: place the bullet slightly above the surface to avoid jittering on repeated contact
                b.y = terrainY + SURFACE_OFFSET;
                b.bounceCount++;

                // AV: reflect velocity using terrain normal for smoother slope-aware bounce
                float dot = b.vx * normal[0] + b.vy * normal[1] + b.vz * normal[2];
                float rx  = b.vx - 2.0f * dot * normal[0];
                float ry  = b.vy - 2.0f * dot * normal[1];
                float rz  = b.vz - 2.0f * dot * normal[2];

                b.vx = rx * LATERAL_DAMPEN;
                b.vy = Math.abs(ry) * BOUNCE_DAMPEN;
                b.vz = rz * LATERAL_DAMPEN;

                // AV: if the bounce is too weak or there have been enough bounces, rest briefly then recycle
                if (b.vy < MIN_VERTICAL_BOUNCE || b.bounceCount >= MAX_BOUNCES) {
                    b.resting = true;
                    b.restTimer = REST_TIME;
                    b.vx = 0.0f;
                    b.vy = 0.0f;
                    b.vz = 0.0f;
                }
            }

            // AV: emergency cleanup if a bullet somehow falls out of the world
            return b.y < KILL_Y;
        });
    }

    private void spawnBullet() {
        float x = minX + rng.nextFloat() * (maxX - minX);
        float z = minZ + rng.nextFloat() * (maxZ - minZ);
        boolean large = rng.nextBoolean();

        // NEW: give each bullet a small random horizontal drift so bounces spread naturally
        float vx = (rng.nextFloat() - 0.5f) * 4.0f; // NEW
        float vz = (rng.nextFloat() - 0.5f) * 4.0f; // NEW

        bullets.add(new Bullet(x, SPAWN_HEIGHT, z, large, vx, -FALL_SPEED, vz)); // NEW: initial velocity
    }

    public void render() {
        // AV: disable lighting so bullet PNG colors show without scene tint
        GL11.glDisable(GL11.GL_LIGHTING);

        for (Bullet b : bullets) {
            GL11.glPushMatrix();

            GL11.glTranslatef(b.x, b.y, b.z);

            // AV: make sure bullet textures render at full brightness
            GL11.glColor3f(1.0f, 1.0f, 1.0f);

            // NEW: tilt bullet to match velocity direction while it's moving; lay flat when resting
            if (!b.resting) {
                float speed = (float) Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
                if (speed > 0.01f) {
                    // NEW: point the bullet nose along its velocity vector
                    float pitch = (float) Math.toDegrees(Math.asin(-b.vy / speed));
                    float yaw   = (float) Math.toDegrees(Math.atan2(b.vx, b.vz));
                    GL11.glRotatef(yaw,   0, 1, 0); // NEW: horizontal heading
                    GL11.glRotatef(pitch, 1, 0, 0); // NEW: nose-down angle
                }
            } else {
                // ST: rotate so bullet points nose-down (falling posture)
                GL11.glRotatef(180, 1, 0, 0);

            }

            if (b.large) {
                GL11.glScalef(LARGE_SCALE, LARGE_SCALE, LARGE_SCALE);

                GL11.glRotatef(90, 1, 0, 0);  // rotate 90° around X to stand it upright

                // AV: bind the large bullet PNG before rendering the large bullet mesh
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, largeBulletTexture);

                largeMesh.render();
            } else {
                GL11.glScalef(SMALL_SCALE, SMALL_SCALE, SMALL_SCALE);

                // AV: bind the small bullet PNG before rendering the small bullet mesh
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, smallBulletTexture);

                smallMesh.render();
            }

            GL11.glPopMatrix();
        }

        // AV: restore lighting for the rest of the scene
        GL11.glEnable(GL11.GL_LIGHTING);
    }

    private static class Bullet {
        float x, y, z;
        float vx, vy, vz; // NEW: velocity components
        boolean large;
        boolean resting;  // NEW: true once bullet has come to rest on terrain
        int bounceCount;  // NEW: tracks how many times the bullet has bounced
        float restTimer;  // AV: how long the bullet stays visible before being removed

        // NEW: updated constructor to accept initial velocity
        Bullet(float x, float y, float z, boolean large, float vx, float vy, float vz) {
            this.x = x; this.y = y; this.z = z;
            this.large = large;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.resting = false; // NEW
            this.bounceCount = 0; // NEW
            this.restTimer = 0.0f; // AV: initialize recycle timer
        }
    }
}

// ST: loads and renders an OBJ mesh; parses vn normals for lighting
class OBJMesh {
    private final List<float[]> vertices  = new ArrayList<>();
    private final List<float[]> normals   = new ArrayList<>(); // ST: for lighting
    private final List<float[]> texCoords = new ArrayList<>();
    private final List<int[]>   faces     = new ArrayList<>(); // 9 ints: v,vt,vn x3

    public OBJMesh(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("vn ")) {
                    String[] t = line.split("\\s+");
                    normals.add(new float[]{
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2]),
                            Float.parseFloat(t[3])
                    });
                } else if (line.startsWith("v ")) {
                    String[] t = line.split("\\s+");
                    vertices.add(new float[]{
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2]),
                            Float.parseFloat(t[3])
                    });
                } else if (line.startsWith("vt ")) {
                    String[] t = line.split("\\s+");
                    texCoords.add(new float[]{
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2])
                    });
                } else if (line.startsWith("f ")) {
                    // ST: supports v, v/vt, v/vt/vn, v//vn
                    String[] t = line.split("\\s+");
                    int[] face = new int[9];
                    for (int i = 0; i < 3; i++) {
                        String[] parts = t[i + 1].split("/");
                        face[i*3]     = Integer.parseInt(parts[0]) - 1;
                        face[i*3 + 1] = (parts.length > 1 && !parts[1].isEmpty())
                                ? Integer.parseInt(parts[1]) - 1 : -1;
                        face[i*3 + 2] = (parts.length > 2 && !parts[2].isEmpty())
                                ? Integer.parseInt(parts[2]) - 1 : -1;
                    }
                    faces.add(face);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (texCoords.isEmpty()) texCoords.add(new float[]{0, 0});
        if (normals.isEmpty())   normals.add(new float[]{0, 1, 0});
    }

    public void render() {
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int[] f : faces) {
            for (int i = 0; i < 3; i++) {
                int vni = f[i*3 + 2];
                int vti = f[i*3 + 1];
                int vi  = f[i*3];

                if (vni >= 0) { float[] n = normals.get(vni);   GL11.glNormal3f(n[0], n[1], n[2]); }
                if (vti >= 0) { float[] uv = texCoords.get(vti); GL11.glTexCoord2f(uv[0], uv[1]); }
                float[] v = vertices.get(vi);
                GL11.glVertex3f(v[0], v[1], v[2]);
            }
        }
        GL11.glEnd();
    }

    // NEW: expose vertex and normal data so Terrain can build a height/normal lookup
    public List<float[]> getVertices() { return vertices; } // NEW
    public List<float[]> getNormals()  { return normals;  } // NEW
    public List<int[]>   getFaces()    { return faces;    } // NEW
}

// ST: static texture loader — RGBA so alpha channels work
class TextureLoader {
    private static int nextPow2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    public static int load(String path) {
        try {
            BufferedImage img = ImageIO.read(new File(path));
            int w = img.getWidth(), h = img.getHeight();

            // Scale up to power-of-two dimensions if needed
            int pw = nextPow2(w);
            int ph = nextPow2(h);
            if (pw != w || ph != h) {
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(pw, ph, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, pw, ph, null);
                g.dispose();
                img = scaled;
                w = pw;
                h = ph;
            }

            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);

            java.nio.ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
            for (int pixel : pixels) {
                buffer.put((byte)((pixel >> 16) & 0xFF)); // R
                buffer.put((byte)((pixel >> 8)  & 0xFF)); // G
                buffer.put((byte)(pixel         & 0xFF)); // B
                buffer.put((byte)((pixel >> 24) & 0xFF)); // A
            }
            buffer.flip();

            int texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            return texId;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }
}

//ST: Terrain class for OBJ mesh + texture
class Terrain {
    private final OBJMesh mesh;
    private int textureId;

    private float minX = Float.MAX_VALUE,  maxX = -Float.MAX_VALUE;
    private float minZ = Float.MAX_VALUE,  maxZ = -Float.MAX_VALUE;

    // AV: flatten factor used by both terrain rendering and collision sampling
    private static final float TERRAIN_FLATTEN_SCALE = 0.35f;

    // NEW: heightmap and normal map arrays for fast bullet collision sampling
    private int   gridW, gridH;           // NEW: grid dimensions (256x256 for fractal_terrain)
    private float[] heightGrid;           // NEW: [gridW * gridH] sampled heights
    private float[][] normalGrid;         // NEW: [gridW * gridH][3] sampled normals

    public Terrain(String objPath, String texturePath) {
        mesh = new OBJMesh(objPath);
        parseBounds(objPath);
        textureId = TextureLoader.load(texturePath);
        buildHeightGrid(); // NEW: build lookup table after mesh is loaded
    }

    // NEW: walks vertices to populate a fast height + normal lookup grid
    private void buildHeightGrid() {
        // NEW: the fractal_terrain OBJ lays out vertices as integer x,z coords (0..255)
        // We can compute grid dimensions from min/max x and z
        gridW = Math.round(maxX - minX) + 1; // NEW
        gridH = Math.round(maxZ - minZ) + 1; // NEW

        heightGrid = new float[gridW * gridH]; // NEW
        normalGrid = new float[gridW * gridH][]; // NEW

        // NEW: first pass — record height at each integer grid cell from vertex list
        java.util.List<float[]> verts   = mesh.getVertices(); // NEW
        java.util.List<float[]> normals = mesh.getNormals();  // NEW
        java.util.List<int[]>   faces   = mesh.getFaces();    // NEW

        // NEW: accumulate normals per grid cell (average across shared faces)
        float[][] normAccum = new float[gridW * gridH][3]; // NEW
        int[]     normCount = new int[gridW * gridH];      // NEW

        // NEW: build height grid from vertex positions
        for (float[] v : verts) { // NEW
            int xi = Math.round(v[0] - minX); // NEW
            int zi = Math.round(v[2] - minZ); // NEW
            if (xi >= 0 && xi < gridW && zi >= 0 && zi < gridH) { // NEW
                heightGrid[zi * gridW + xi] = v[1]; // NEW: y is the height
            } // NEW
        } // NEW

        // NEW: accumulate vertex normals per grid cell
        for (int[] f : faces) { // NEW
            for (int i = 0; i < 3; i++) { // NEW
                int vi  = f[i*3];     // NEW
                int vni = f[i*3 + 2]; // NEW
                if (vi < 0 || vi >= verts.size()) continue; // NEW
                float[] v = verts.get(vi); // NEW
                int xi = Math.round(v[0] - minX); // NEW
                int zi = Math.round(v[2] - minZ); // NEW
                if (xi < 0 || xi >= gridW || zi < 0 || zi >= gridH) continue; // NEW
                int idx = zi * gridW + xi; // NEW
                if (vni >= 0 && vni < normals.size()) { // NEW
                    float[] n = normals.get(vni); // NEW
                    normAccum[idx][0] += n[0]; // NEW
                    normAccum[idx][1] += n[1]; // NEW
                    normAccum[idx][2] += n[2]; // NEW
                    normCount[idx]++; // NEW
                } // NEW
            } // NEW
        } // NEW

        // NEW: finalise normal grid — average and normalise each cell
        for (int i = 0; i < gridW * gridH; i++) { // NEW
            if (normCount[i] > 0) { // NEW
                float nx = normAccum[i][0] / normCount[i]; // NEW
                float ny = normAccum[i][1] / normCount[i]; // NEW
                float nz = normAccum[i][2] / normCount[i]; // NEW
                float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz); // NEW
                if (len > 0) { nx /= len; ny /= len; nz /= len; } // NEW
                normalGrid[i] = new float[]{nx, ny, nz}; // NEW
            } else { // NEW
                normalGrid[i] = new float[]{0, 1, 0}; // NEW: default flat normal
            } // NEW
        } // NEW
    } // NEW

    // NEW: bilinearly interpolate terrain height at any world (x, z) position
    public float getHeightAt(float wx, float wz) { // NEW
        float gx = wx - minX; // NEW: convert world to grid coords
        float gz = wz - minZ; // NEW

        int x0 = (int) Math.floor(gx); // NEW
        int z0 = (int) Math.floor(gz); // NEW
        int x1 = x0 + 1; // NEW
        int z1 = z0 + 1; // NEW

        // NEW: clamp to grid bounds
        x0 = Math.max(0, Math.min(gridW - 1, x0)); // NEW
        z0 = Math.max(0, Math.min(gridH - 1, z0)); // NEW
        x1 = Math.max(0, Math.min(gridW - 1, x1)); // NEW
        z1 = Math.max(0, Math.min(gridH - 1, z1)); // NEW

        float tx = gx - (int) Math.floor(gx); // NEW: fractional part for lerp
        float tz = gz - (int) Math.floor(gz); // NEW

        // NEW: bilinear interpolation across the four surrounding grid corners
        float h00 = heightGrid[z0 * gridW + x0]; // NEW
        float h10 = heightGrid[z0 * gridW + x1]; // NEW
        float h01 = heightGrid[z1 * gridW + x0]; // NEW
        float h11 = heightGrid[z1 * gridW + x1]; // NEW

        float h0 = h00 + tx * (h10 - h00); // NEW: lerp along x at z0
        float h1 = h01 + tx * (h11 - h01); // NEW: lerp along x at z1

        // AV: flatten sampled terrain height so bullet collision matches the flatter rendered terrain
        return (h0 + tz * (h1 - h0)) * TERRAIN_FLATTEN_SCALE;
    } // NEW

    // NEW: return interpolated surface normal at world (x, z) — used for bounce reflection
    public float[] getNormalAt(float wx, float wz) { // NEW
        float gx = wx - minX; // NEW
        float gz = wz - minZ; // NEW

        int x0 = Math.max(0, Math.min(gridW - 1, (int) Math.floor(gx))); // NEW
        int z0 = Math.max(0, Math.min(gridH - 1, (int) Math.floor(gz))); // NEW
        int x1 = Math.max(0, Math.min(gridW - 1, x0 + 1)); // NEW
        int z1 = Math.max(0, Math.min(gridH - 1, z0 + 1)); // NEW

        float tx = gx - (int) Math.floor(gx); // NEW
        float tz = gz - (int) Math.floor(gz); // NEW

        float[] n00 = normalGrid[z0 * gridW + x0]; // NEW
        float[] n10 = normalGrid[z0 * gridW + x1]; // NEW
        float[] n01 = normalGrid[z1 * gridW + x0]; // NEW
        float[] n11 = normalGrid[z1 * gridW + x1]; // NEW

        // NEW: bilinearly interpolate normal components
        float nx = n00[0]*(1-tx)*(1-tz) + n10[0]*tx*(1-tz) + n01[0]*(1-tx)*tz + n11[0]*tx*tz; // NEW
        float ny = n00[1]*(1-tx)*(1-tz) + n10[1]*tx*(1-tz) + n01[1]*(1-tx)*tz + n11[1]*tx*tz; // NEW
        float nz = n00[2]*(1-tx)*(1-tz) + n10[2]*tx*(1-tz) + n01[2]*(1-tx)*tz + n11[2]*tx*tz; // NEW

        // AV: bias the normal upward so bouncing behaves more like flatter ground
        ny *= 2.5f;

        // NEW: re-normalise after interpolation
        float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz); // NEW
        if (len > 0) { nx /= len; ny /= len; nz /= len; } // NEW
        else { ny = 1; } // NEW: fallback to flat up

        return new float[]{nx, ny, nz}; // NEW
    } // NEW

    private void parseBounds(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("v ")) {
                    String[] t = line.split("\\s+");
                    float x = Float.parseFloat(t[1]);
                    float z = Float.parseFloat(t[3]);
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    public void render() {
        GL11.glPushMatrix();
        GL11.glScalef(1.0f, TERRAIN_FLATTEN_SCALE, 1.0f); // AV: flatten terrain visually so hills are less steep
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        mesh.render();
        GL11.glPopMatrix();
    }

    public float getCenterX()      { return (minX + maxX) / 2.0f; }
    public float getCenterY()      { return (minZ + maxZ) / 2.0f; }
    public float getMaxDimension() { return Math.max(maxX - minX, maxZ - minZ); }
    public float getMinX() { return minX; }
    public float getMaxX() { return maxX; }
    public float getMinZ() { return minZ; }
    public float getMaxZ() { return maxZ; }
}

// AV: sound player using multiple clips so impact sounds can overlap
class SoundPlayer {
    private Clip[] clips; // AV: pool of clips so many bullet hits can play close together
    private FloatControl[] gainControls; // AV: volume control for each clip
    private int nextClip = 0; // AV: rotates through the clip pool

    public void load(String path) {
        try {
            int poolSize = 12; // AV: number of overlapping impact sounds allowed
            clips = new Clip[poolSize];
            gainControls = new FloatControl[poolSize];

            for (int i = 0; i < poolSize; i++) {
                AudioInputStream audio = AudioSystem.getAudioInputStream(new File(path));
                clips[i] = AudioSystem.getClip();
                clips[i].open(audio);

                if (clips[i].isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    gainControls[i] = (FloatControl) clips[i].getControl(FloatControl.Type.MASTER_GAIN);

                    // AV: boost volume for each clip
                    float max = gainControls[i].getMaximum();
                    gainControls[i].setValue(Math.min(max, 4.0f)); 
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void play() {
        if (clips == null || clips.length == 0) return;

        Clip clip = clips[nextClip];

        if (clip.isRunning()) {
            clip.stop(); // AV: restart this clip slot if it is still busy
        }

        clip.setFramePosition(0); // AV: rewind selected clip
        clip.start();

        nextClip = (nextClip + 1) % clips.length; // AV: move to next clip for overlap
    }
}