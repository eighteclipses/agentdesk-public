package com.agentdesk.security;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class PasswordService {
  private static final int ITERATIONS = 120000;
  private static final int KEY_BITS = 256;
  private final SecureRandom random = new SecureRandom();
  public String hash(String password) {
    try {
      byte[] salt=new byte[16]; random.nextBytes(salt);
      return encode(salt,derive(password.toCharArray(),salt,ITERATIONS));
    } catch(Exception e){throw new IllegalStateException("密码加密失败",e);}
  }
  public boolean matches(String password,String stored) {
    try {
      if(stored==null||!stored.startsWith("pbkdf2$")) return false;
      String[] p=stored.split("\\$",4); int iterations=Integer.parseInt(p[1]); byte[] salt=Base64.getDecoder().decode(p[2]); byte[] expected=Base64.getDecoder().decode(p[3]); byte[] actual=derive(password.toCharArray(),salt,iterations); return java.security.MessageDigest.isEqual(expected,actual);
    } catch(Exception e){return false;}
  }
  private byte[] derive(char[] password,byte[] salt,int iterations)throws Exception{PBEKeySpec spec=new PBEKeySpec(password,salt,iterations,KEY_BITS);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}}
  private String encode(byte[] salt,byte[] key){return "pbkdf2$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(key);}
}
