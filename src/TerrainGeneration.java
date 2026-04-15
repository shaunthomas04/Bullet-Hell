//javac -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainGeneration.java
//java -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainGeneration

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

public class TerrainGeneration {
    private long window;
    private int width = 1920;
    private int height = 1080;
    private Terrain terrain;
    private BulletSystem bulletSystem;
    private SoundPlayer soundPlayer;

    public static void main(String[] args) {
        new TerrainGeneration().run();
    }

    public void run() {
        init();
        loop();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private void init() {
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 4);
        window = GLFW.glfwCreateWindow(width, height, "500 Bullets = WOW", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        GL11.glEnable(GL13.GL_MULTISAMPLE);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        // --- BACKGROUND HUE CHANGE ---
        // Change the background color from the default dark tone to a dark reddish hue.
        // Format: ClearColor(Red, Green, Blue, Alpha)
        // Adjust these values to fine-tune the color.
        GL11.glClearColor(0.40f, 0.15f, 0.15f, 1.0f); // Original was GL11.glClearColor(0.2f, 0.15f, 0.15f, 1.0f);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        setPerspectiveProjection(60.0f, (float) width / height, 0.1f, 2000.0f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        GL11.glEnable(GL11.GL_TEXTURE_2D);

        // --- ENHANCED LIGHTING FOR REFLECTIONS ---
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);

        // 1. Position light far off-screen (Top-Right-Front)
        FloatBuffer lightPos = BufferUtils.createFloatBuffer(4);
        lightPos.put(new float[]{200.0f, 400.0f, 200.0f, 1.0f}).flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPos);

        // 2. Diffuse Light (Base Color)
        FloatBuffer lightDiff = BufferUtils.createFloatBuffer(4);
        lightDiff.put(new float[]{1.0f, 0.9f, 0.7f, 1.0f}).flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, lightDiff);

        // 3. Specular Light (The actual "Shine" of the light source)
        FloatBuffer lightSpec = BufferUtils.createFloatBuffer(4);
        lightSpec.put(new float[]{1.0f, 1.0f, 1.0f, 1.0f}).flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_SPECULAR, lightSpec);

        FloatBuffer lightAmb = BufferUtils.createFloatBuffer(4);
        lightAmb.put(new float[]{0.2f, 0.2f, 0.25f, 1.0f}).flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_AMBIENT, lightAmb);

        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_NORMALIZE);

        terrain = new Terrain("fractal_terrain.obj", "terrain.png");

        // AV: audio setup for bullet impacts
        soundPlayer = new SoundPlayer();
        soundPlayer.loadAll(new String[]{
                "impact0.wav",
                "impact1.wav",
                "impact2.wav"
        });

        bulletSystem = new BulletSystem(
                terrain.getMinX(), terrain.getMaxX(),
                terrain.getMinZ(), terrain.getMaxZ(),
                terrain,
                soundPlayer
        );
    }

    private void loop() {
        long lastTime = System.nanoTime();

        while (!GLFW.glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            float dt = (now - lastTime) / 1_000_000_000.0f;
            lastTime = now;

            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glLoadIdentity();

            float centerX = terrain.getCenterX();
            float maxDim  = terrain.getMaxDimension();

            GL11.glTranslatef(-centerX, -maxDim * 0.24f, -maxDim * 1.4f);

            // Render terrain normally
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
            terrain.render();

            bulletSystem.update(dt);
            bulletSystem.render();

            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    private void setPerspectiveProjection(float fov, float aspect, float zNear, float zFar) {
        float ymax = (float)(zNear * Math.tan(Math.toRadians(fov / 2.0)));
        float xmax = ymax * aspect;

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glFrustum(-xmax, xmax, -ymax, ymax, zNear, zFar);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}

class BulletSystem {
    private static final int   MAX_BULLETS  = 160;
    private static final float SPAWN_RATE   = 18.0f;
    private static final float FALL_SPEED   = 18.0f;
    private static final float SPAWN_HEIGHT = 200.0f;
    private static final float KILL_Y       = -10.0f;
    private static final float SMALL_SCALE = 1.2f;
    private static final float LARGE_SCALE = 5.2f;

    private static final float GRAVITY             = 25.0f;
    private static final float BOUNCE_DAMPEN       = 0.35f;
    private static final float LATERAL_DAMPEN      = 0.82f;
    private static final float MIN_VERTICAL_BOUNCE = 1.2f;
    private static final int   MAX_BOUNCES         = 4;
    private static final float SURFACE_OFFSET      = 0.08f;
    private static final float REST_TIME           = 1.0f;
    private static final float MIN_IMPACT_SOUND_SPEED = 4.0f;

    private final List<Bullet> bullets = new ArrayList<>();
    private final Random rng = new Random();
    private float spawnAccumulator = 0.0f;

    private final float minX, maxX, minZ, maxZ;
    private final OBJMesh smallMesh;
    private final OBJMesh largeMesh;
    private final int smallBulletTexture;
    private final int largeBulletTexture;
    private final Terrain terrain;
    private final SoundPlayer soundPlayer;

    public BulletSystem(float minX, float maxX, float minZ, float maxZ, Terrain terrain, SoundPlayer soundPlayer) {
        this.minX = minX; this.maxX = maxX;
        this.minZ = minZ; this.maxZ = maxZ;
        this.terrain = terrain;
        this.soundPlayer = soundPlayer;

        smallMesh = new OBJMesh("small_bullet.obj");
        largeMesh = new OBJMesh("large_bullet.obj");
        smallBulletTexture = TextureLoader.load("small_bullet.png");
        largeBulletTexture = TextureLoader.load("large_bullet.png");
    }

    public void update(float dt) {
        spawnAccumulator += dt * SPAWN_RATE;
        while (spawnAccumulator >= 1.0f && bullets.size() < MAX_BULLETS) {
            spawnAccumulator -= 1.0f;
            spawnBullet();
        }

        bullets.removeIf(b -> {
            if (b.resting) {
                b.restTimer -= dt;
                return b.restTimer <= 0.0f;
            }
            float prevY = b.y;
            b.vy -= GRAVITY * dt;
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.z += b.vz * dt;

            b.x = Math.max(minX, Math.min(maxX, b.x));
            b.z = Math.max(minZ, Math.min(maxZ, b.z));

            float terrainY = terrain.getHeightAt(b.x, b.z);

            if (prevY > terrainY && b.y <= terrainY && b.vy < 0.0f) {
                if (Math.abs(b.vy) > MIN_IMPACT_SOUND_SPEED) {
                    soundPlayer.play();
                }

                float[] normal = terrain.getNormalAt(b.x, b.z);
                b.y = terrainY + SURFACE_OFFSET;
                b.bounceCount++;
                float dot = b.vx * normal[0] + b.vy * normal[1] + b.vz * normal[2];
                float rx  = b.vx - 2.0f * dot * normal[0];
                float ry  = b.vy - 2.0f * dot * normal[1];
                float rz  = b.vz - 2.0f * dot * normal[2];

                b.vx = rx * LATERAL_DAMPEN;
                b.vy = Math.abs(ry) * BOUNCE_DAMPEN;
                b.vz = rz * LATERAL_DAMPEN;

                if (b.vy < MIN_VERTICAL_BOUNCE || b.bounceCount >= MAX_BOUNCES) {
                    b.resting = true;
                    b.restTimer = REST_TIME;
                    b.vx = 0.0f;
                    b.vy = 0.0f;
                    b.vz = 0.0f;
                }
            }
            return b.y < KILL_Y;
        });
    }

    private void spawnBullet() {
        float x = minX + rng.nextFloat() * (maxX - minX);
        float z = minZ + rng.nextFloat() * (maxZ - minZ);
        boolean large = rng.nextBoolean();
        float vx = (rng.nextFloat() - 0.5f) * 4.0f;
        float vz = (rng.nextFloat() - 0.5f) * 4.0f;
        bullets.add(new Bullet(x, SPAWN_HEIGHT, z, large, vx, -FALL_SPEED, vz));
    }

    public void render() {
        // --- CONFIGURE METALLIC SHINE ---
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL); // Materials used for better highlights

        // Sharp white specular highlights
        FloatBuffer matSpec = BufferUtils.createFloatBuffer(4);
        matSpec.put(new float[]{1.0f, 1.0f, 1.0f, 1.0f}).flip();
        GL11.glMaterialfv(GL11.GL_FRONT, GL11.GL_SPECULAR, matSpec);

        // Shininess: 0-128. Higher is a smaller, sharper metallic "glint"
        GL11.glMateriali(GL11.GL_FRONT, GL11.GL_SHININESS, 110);

        for (Bullet b : bullets) {
            GL11.glPushMatrix();
            GL11.glTranslatef(b.x, b.y, b.z);

            if (!b.resting) {
                float speed = (float) Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
                if (speed > 0.01f) {
                    float pitch = (float) Math.toDegrees(Math.asin(-b.vy / speed));
                    float yaw   = (float) Math.toDegrees(Math.atan2(b.vx, b.vz));
                    GL11.glRotatef(yaw,   0, 1, 0);
                    GL11.glRotatef(pitch, 1, 0, 0);
                }
            } else {
                GL11.glRotatef(180, 1, 0, 0);
            }

            if (b.large) {
                GL11.glScalef(LARGE_SCALE, LARGE_SCALE, LARGE_SCALE);
                GL11.glRotatef(90, 1, 0, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, largeBulletTexture);
                largeMesh.render();
            } else {
                GL11.glScalef(SMALL_SCALE, SMALL_SCALE, SMALL_SCALE);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, smallBulletTexture);
                smallMesh.render();
            }
            GL11.glPopMatrix();
        }

        // Restore default lighting state
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
    }

    private static class Bullet {
        float x, y, z, vx, vy, vz;
        boolean large, resting;
        int bounceCount;
        float restTimer;

        Bullet(float x, float y, float z, boolean large, float vx, float vy, float vz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.large = large;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.resting = false;
            this.bounceCount = 0;
            this.restTimer = 0.0f;
        }
    }
}

class OBJMesh {
    private final List<float[]> vertices  = new ArrayList<>();
    private final List<float[]> normals   = new ArrayList<>();
    private final List<float[]> texCoords = new ArrayList<>();
    private final List<int[]>   faces     = new ArrayList<>();

    public OBJMesh(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("vn ")) {
                    String[] t = line.split("\\s+");
                    normals.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                } else if (line.startsWith("v ")) {
                    String[] t = line.split("\\s+");
                    vertices.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                } else if (line.startsWith("vt ")) {
                    String[] t = line.split("\\s+");
                    texCoords.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2])});
                } else if (line.startsWith("f ")) {
                    String[] t = line.split("\\s+");
                    int[] face = new int[9];
                    for (int i = 0; i < 3; i++) {
                        String[] parts = t[i + 1].split("/");
                        face[i*3]     = Integer.parseInt(parts[0]) - 1;
                        face[i*3 + 1] = (parts.length > 1 && !parts[1].isEmpty()) ? Integer.parseInt(parts[1]) - 1 : -1;
                        face[i*3 + 2] = (parts.length > 2 && !parts[2].isEmpty()) ? Integer.parseInt(parts[2]) - 1 : -1;
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
                if (vni >= 0) {
                    float[] n = normals.get(vni);
                    GL11.glNormal3f(n[0], n[1], n[2]);
                }
                if (vti >= 0) {
                    float[] uv = texCoords.get(vti);
                    GL11.glTexCoord2f(uv[0], uv[1]);
                }
                float[] v = vertices.get(vi);
                GL11.glVertex3f(v[0], v[1], v[2]);
            }
        }
        GL11.glEnd();
    }

    public List<float[]> getVertices() { return vertices; }
    public List<float[]> getNormals()  { return normals;  }
    public List<int[]>   getFaces()    { return faces;    }
}

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
            int pw = nextPow2(w), ph = nextPow2(h);
            if (pw != w || ph != h) {
                BufferedImage sc = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
                sc.getGraphics().drawImage(img, 0, 0, pw, ph, null);
                img = sc;
                w = pw;
                h = ph;
            }
            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);
            java.nio.ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
            for (int p : pixels) {
                buffer.put((byte)((p >> 16) & 0xFF));
                buffer.put((byte)((p >> 8)  & 0xFF));
                buffer.put((byte)(p         & 0xFF));
                buffer.put((byte)((p >> 24) & 0xFF));
            }
            buffer.flip();
            int texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            return texId;
        } catch (IOException e) {
            return 0;
        }
    }
}

class Terrain {
    private final OBJMesh mesh;
    private int textureId;
    private float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
    private static final float TERRAIN_FLATTEN_SCALE = 0.35f;
    private int gridW, gridH;
    private float[] heightGrid;
    private float[][] normalGrid;

    public Terrain(String objPath, String texturePath) {
        mesh = new OBJMesh(objPath);
        parseBounds(objPath);
        textureId = TextureLoader.load(texturePath);
        buildHeightGrid();
    }

    private void buildHeightGrid() {
        gridW = Math.round(maxX - minX) + 1;
        gridH = Math.round(maxZ - minZ) + 1;
        heightGrid = new float[gridW * gridH];
        normalGrid = new float[gridW * gridH][];
        List<float[]> verts = mesh.getVertices();
        List<float[]> norms = mesh.getNormals();
        List<int[]> faces = mesh.getFaces();
        float[][] normAcc = new float[gridW * gridH][3];
        int[] normCnt = new int[gridW * gridH];

        for (float[] v : verts) {
            int xi = Math.round(v[0] - minX), zi = Math.round(v[2] - minZ);
            if (xi >= 0 && xi < gridW && zi >= 0 && zi < gridH) {
                heightGrid[zi * gridW + xi] = v[1];
            }
        }

        for (int[] f : faces) {
            for (int i = 0; i < 3; i++) {
                int vi = f[i*3], vni = f[i*3+2];
                if (vi < 0 || vi >= verts.size()) continue;
                float[] v = verts.get(vi);
                int xi = Math.round(v[0]-minX), zi = Math.round(v[2]-minZ);
                if (xi >= 0 && xi < gridW && zi >= 0 && zi < gridH && vni >= 0 && vni < norms.size()) {
                    float[] n = norms.get(vni);
                    normAcc[zi*gridW+xi][0] += n[0];
                    normAcc[zi*gridW+xi][1] += n[1];
                    normAcc[zi*gridW+xi][2] += n[2];
                    normCnt[zi*gridW+xi]++;
                }
            }
        }

        for (int i = 0; i < gridW*gridH; i++) {
            if (normCnt[i] > 0) {
                float nx = normAcc[i][0]/normCnt[i], ny = normAcc[i][1]/normCnt[i], nz = normAcc[i][2]/normCnt[i];
                float len = (float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                normalGrid[i] = (len > 0) ? new float[]{nx/len, ny/len, nz/len} : new float[]{0,1,0};
            } else {
                normalGrid[i] = new float[]{0,1,0};
            }
        }
    }

    public float getHeightAt(float wx, float wz) {
        float gx = wx - minX, gz = wz - minZ;
        int x0 = (int)Math.floor(gx), z0 = (int)Math.floor(gz);
        int x1 = Math.min(gridW-1, x0+1), z1 = Math.min(gridH-1, z0+1);
        x0 = Math.max(0, Math.min(gridW-1, x0));
        z0 = Math.max(0, Math.min(gridH-1, z0));
        float tx = gx - x0, tz = gz - z0;
        float h00 = heightGrid[z0*gridW+x0], h10 = heightGrid[z0*gridW+x1], h01 = heightGrid[z1*gridW+x0], h11 = heightGrid[z1*gridW+x1];
        return (h00 + (h10 - h00) * tx + (h01 + (h11 - h01) * tx - (h00 + (h10 - h00) * tx)) * tz) * TERRAIN_FLATTEN_SCALE;
    }

    public float[] getNormalAt(float wx, float wz) {
        int gx = Math.max(0, Math.min(gridW-1, Math.round(wx-minX)));
        int gz = Math.max(0, Math.min(gridH-1, Math.round(wz-minZ)));
        return normalGrid[gz*gridW+gx];
    }

    private void parseBounds(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("v ")) {
                    String[] t = line.trim().split("\\s+");
                    float x = Float.parseFloat(t[1]), z = Float.parseFloat(t[3]);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
        } catch (IOException e) {
        }
    }

    public void render() {
        GL11.glPushMatrix();
        GL11.glScalef(1.0f, TERRAIN_FLATTEN_SCALE, 1.0f);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        mesh.render();
        GL11.glPopMatrix();
    }

    public float getCenterX() { return (minX+maxX)/2; }
    public float getMaxDimension() { return Math.max(maxX-minX, maxZ-minZ); }
    public float getMinX() { return minX; }
    public float getMaxX() { return maxX; }
    public float getMinZ() { return minZ; }
    public float getMaxZ() { return maxZ; }
}

// AV: random impact audio system - loads 3 wav files and plays one random sound whenever a bullet hits terrain
class SoundPlayer {
    private static final int CLIPS_PER_SOUND = 8;

    private Clip[][] clipPools;
    private int[] nextClipIndex;
    private final Random rng = new Random();

    public void loadAll(String[] paths) {
        try {
            clipPools = new Clip[paths.length][CLIPS_PER_SOUND];
            nextClipIndex = new int[paths.length];

            for (int soundIndex = 0; soundIndex < paths.length; soundIndex++) {
                for (int clipIndex = 0; clipIndex < CLIPS_PER_SOUND; clipIndex++) {
                    AudioInputStream audio = AudioSystem.getAudioInputStream(new File(paths[soundIndex]));
                    clipPools[soundIndex][clipIndex] = AudioSystem.getClip();
                    clipPools[soundIndex][clipIndex].open(audio);

                    if (clipPools[soundIndex][clipIndex].isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                        FloatControl gain = (FloatControl) clipPools[soundIndex][clipIndex]
                                .getControl(FloatControl.Type.MASTER_GAIN);
                        gain.setValue(gain.getMaximum());
                    }
                }
            }
        } catch (Exception e) {
            clipPools = null;
            nextClipIndex = null;
            e.printStackTrace();
        }
    }

    public void play() {
        if (clipPools == null || clipPools.length == 0) return;

        int soundIndex = rng.nextInt(clipPools.length);

        if (clipPools[soundIndex] == null) return;

        Clip[] pool = clipPools[soundIndex];
        int clipIndex = nextClipIndex[soundIndex];

        if (pool == null || clipIndex < 0 || clipIndex >= pool.length) return;
        if (pool[clipIndex] == null) return;

        Clip clip = pool[clipIndex];

        if (clip.isRunning()) {
            clip.stop();
        }

        clip.setFramePosition(0);
        clip.start();

        nextClipIndex[soundIndex] = (clipIndex + 1) % pool.length;
    }
}