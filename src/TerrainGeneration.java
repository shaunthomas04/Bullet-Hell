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

import javax.imageio.ImageIO; //ST

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

public class TerrainGeneration { //ST
    private long window;
    private int width = 800;
    private int height = 600;
    private Terrain terrain; //ST
    private BulletSystem bulletSystem; //ST: bullet rain

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

        window = GLFW.glfwCreateWindow(width, height, "Terrain Generation - Bullet Rain", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        // ST: sky-blue background so bullets stand out against the scene
        GL11.glClearColor(0.53f, 0.81f, 0.98f, 1.0f);

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

        // ST: init bullet system using terrain bounds so bullets spawn above the terrain
        bulletSystem = new BulletSystem(
                terrain.getMinX(), terrain.getMaxX(),
                terrain.getMinZ(), terrain.getMaxZ()
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

            // ST: steep top-down-ish angle so bullets are clearly visible falling in
            float centerX = terrain.getCenterX();
            float centerZ = terrain.getCenterY(); // getCenterY() returns Z midpoint
            float maxDim  = terrain.getMaxDimension();

            GL11.glRotatef(55, 1, 0, 0);                                          // steep downward tilt
            GL11.glTranslatef(-centerX, -maxDim * 0.6f, -centerZ - maxDim * 0.7f);

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
    private static final int   MAX_BULLETS  = 80;    // plenty of bullets visible at once
    private static final float SPAWN_RATE   = 12.0f; // bullets per second
    private static final float FALL_SPEED   = 18.0f; // units per second downward
    private static final float SPAWN_HEIGHT = 50.0f; // high enough to see them falling in
    private static final float KILL_Y       = -5.0f; // cull once below terrain surface

    // ST: scale large enough to be clearly visible in the scene
    private static final float SMALL_SCALE = 1.8f;
    private static final float LARGE_SCALE = 3.2f;

    private final List<Bullet> bullets = new ArrayList<>();
    private final Random rng = new Random();
    private float spawnAccumulator = 0.0f;

    private final float minX, maxX, minZ, maxZ;

    private final OBJMesh smallMesh;
    private final OBJMesh largeMesh;
    private final int smallTexture;
    private final int largeTexture;

    public BulletSystem(float minX, float maxX, float minZ, float maxZ) {
        this.minX = minX; this.maxX = maxX;
        this.minZ = minZ; this.maxZ = maxZ;

        smallMesh    = new OBJMesh("small_bullet.obj");
        largeMesh    = new OBJMesh("large_bullet.obj");
        smallTexture = TextureLoader.load("small_bullet.png");
        largeTexture = TextureLoader.load("large_bullet.png");
    }

    public void update(float dt) {
        spawnAccumulator += dt * SPAWN_RATE;
        while (spawnAccumulator >= 1.0f && bullets.size() < MAX_BULLETS) {
            spawnAccumulator -= 1.0f;
            spawnBullet();
        }

        bullets.removeIf(b -> {
            b.y -= FALL_SPEED * dt;
            return b.y < KILL_Y;
        });
    }

    private void spawnBullet() {
        float x = minX + rng.nextFloat() * (maxX - minX);
        float z = minZ + rng.nextFloat() * (maxZ - minZ);
        boolean large = rng.nextBoolean();
        bullets.add(new Bullet(x, SPAWN_HEIGHT, z, large));
    }

    public void render() {
        for (Bullet b : bullets) {
            GL11.glPushMatrix();

            GL11.glTranslatef(b.x, b.y, b.z);
            // ST: rotate so bullet points nose-down (falling posture)
            GL11.glRotatef(180, 1, 0, 0);

            if (b.large) {
                GL11.glScalef(LARGE_SCALE, LARGE_SCALE, LARGE_SCALE);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, largeTexture);
                largeMesh.render();
            } else {
                GL11.glScalef(SMALL_SCALE, SMALL_SCALE, SMALL_SCALE);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, smallTexture);
                smallMesh.render();
            }

            GL11.glPopMatrix();
        }
    }

    private static class Bullet {
        float x, y, z;
        boolean large;
        Bullet(float x, float y, float z, boolean large) {
            this.x = x; this.y = y; this.z = z; this.large = large;
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
}

// ST: static texture loader — RGBA so alpha channels work
class TextureLoader {
    public static int load(String path) {
        try {
            BufferedImage img = ImageIO.read(new File(path));
            int w = img.getWidth(), h = img.getHeight();
            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);

            FloatBuffer buffer = BufferUtils.createFloatBuffer(w * h * 4);
            for (int pixel : pixels) {
                buffer.put(((pixel >> 16) & 0xFF) / 255.0f); // R
                buffer.put(((pixel >> 8)  & 0xFF) / 255.0f); // G
                buffer.put((pixel         & 0xFF) / 255.0f); // B
                buffer.put(((pixel >> 24) & 0xFF) / 255.0f); // A
            }
            buffer.flip();

            int texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL12.GL_BGRA, GL11.GL_FLOAT, buffer);
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

    public Terrain(String objPath, String texturePath) {
        mesh = new OBJMesh(objPath);
        parseBounds(objPath);
        textureId = TextureLoader.load(texturePath);
    }

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
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        mesh.render();
    }

    public float getCenterX()      { return (minX + maxX) / 2.0f; }
    public float getCenterY()      { return (minZ + maxZ) / 2.0f; }
    public float getMaxDimension() { return Math.max(maxX - minX, maxZ - minZ); }
    public float getMinX() { return minX; }
    public float getMaxX() { return maxX; }
    public float getMinZ() { return minZ; }
    public float getMaxZ() { return maxZ; }
}