package org.crypto.sse ;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import javax.crypto.NoSuchPaddingException;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;


//***********************************************************************************************//

/////////////////////    Implementation of 2Lev scheme of NDSS'15 paper by David Cash Joseph Jaeger Stanislaw Jarecki  Charanjit Jutla Hugo Krawczyk Marcel-Catalin Rosu and Michael Steiner. Finding 
//		the right parameters--- of the array size as well as the threshold to differentiate between large and small database,  to meet the same reported benchmarks is empirically set in the code
//		as it was not reported in the paper. The experimental evaluation of the  scheme is one order of magnitude slower than the numbers reported by Cash as we use Java and not C
//		Plus, some enhancements on the code itself that can be done.

///		This class can be used independently of the IEX-2Lev or IEX-ZMF if needed /////////////////////////////

//***********************************************************************************************//	



public class MMGlobal implements Serializable {





	// define the number of character that a file identifier can have
	public static int	sizeOfFileIdentifer	=	40;



	public static int counter=0;

	public Multimap<String, byte[]> dictionary	=	ArrayListMultimap.create();
	public static List<Integer>	free	=	new ArrayList<Integer>();
	static byte[][]	array	=	null;
	byte[][]	arr	=	null;


	public MMGlobal(Multimap<String, byte[]> dictionary, byte[][] arr){
		this.dictionary	=	dictionary;
		this.arr	=	arr;
	}



	public Multimap<String, byte[]> getDictionary() {
		return dictionary;
	}


	public void setDictionary(Multimap<String, byte[]> dictionary) {
		this.dictionary = dictionary;
	}


	public byte[][] getArray() {
		return arr;
	}


	public void setArray(byte[][] array) {
		this.arr = array;
	}



	//***********************************************************************************************//

	/////////////////////    KeyGenSI	/////////////////////////////

	//***********************************************************************************************//	



	public static byte[] keyGenSI(int keySize, String password, String filePathString, int icount) throws InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException{
		File f = new File(filePathString);
		byte[] salt=null;



		if (f.exists() && !f.isDirectory()){
			salt=CryptoPrimitives.readAlternateImpl(filePathString);
		}
		else{
			salt=CryptoPrimitives.randomBytes(8);
			CryptoPrimitives.write(salt, "saltInvIX", "salt");

		}

		byte[] key=CryptoPrimitives.keyGenSetM(password, salt, icount, keySize);
		return key;

	}



	//***********************************************************************************************//

	/////////////////////    Setup	Parallel/////////////////////////////

	//***********************************************************************************************//	




	public static MMGlobal constructEMMPar(final byte[] key, final Multimap<String, String> lookup, final int bigBlock, final int smallBlock, final int dataSize)
			throws InterruptedException, ExecutionException, IOException {



		Multimap<String, byte[]> dictionary	=	ArrayListMultimap.create();

		System.out.println("\t Initialization of free set \n");

		for (int i=0; i<dataSize;	i++){
			//initialize all buckets with random values
			free.add(i);
		}




		List<String> listOfKeyword	=	new ArrayList<String>(lookup.keySet());
		int threads =0;
		if (Runtime.getRuntime().availableProcessors()>listOfKeyword.size()){
			threads = listOfKeyword.size();
		}
		else{
			threads = Runtime.getRuntime().availableProcessors();
		}


		ExecutorService service = Executors.newFixedThreadPool(threads);
		ArrayList<String[]> inputs=new ArrayList<String[]>(threads);

		System.out.println("\t Partitionning the dictionary for parallel computation \n");


		for (int i=0;i<threads;i++){
			String[] tmp;
			if (i	==	threads-1){
				tmp=new String[listOfKeyword.size()/threads	+	listOfKeyword.size() % threads];
				for (int j=0;j<listOfKeyword.size()/threads	+	listOfKeyword.size() % threads;j++){
					tmp[j]=listOfKeyword.get((listOfKeyword.size()/threads)	*	i	+	j);
				}
			}
			else{
				tmp=new String[listOfKeyword.size()/threads];
				for (int j=0;j<listOfKeyword.size()/threads;j++){

					tmp[j]=listOfKeyword.get((listOfKeyword.size()/threads)	*	i	+	j);
				}
			}
			inputs.add(i, tmp);
		}

		System.out.println("\t End of Partitionning  \n");



		List<Future<Multimap<String, byte[]> >> futures = new ArrayList<Future<Multimap<String, byte[]>  >>();
		for (final String[] input : inputs) {
			Callable<Multimap<String, byte[]>> callable = new Callable<Multimap<String, byte[]> >() {
				public Multimap<String, byte[]>   call() throws Exception {

					Multimap<String, byte[]>  output =	setupSI(key, input, lookup, bigBlock, smallBlock, dataSize);   
					return output;
				}
			};
			futures.add(service.submit(callable));
		}

		service.shutdown();

		for (Future<Multimap<String, byte[]>> future : futures) {
			Set<String> keys	=	future.get().keySet();

			for (String k : keys){
				dictionary.putAll(k,future.get().get(k));			
			}

		}

		return new MMGlobal(dictionary,array);
	}











	//***********************************************************************************************//

	/////////////////////    SetupSI  /////////////////////////////

	//***********************************************************************************************//		


	public static Multimap<String, byte[]> setupSI(byte[] key, String[] listOfKeyword, Multimap<String, String> lookup, int bigBlock, int smallBlock, int dataSize) throws InvalidKeyException, InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, IOException
	{




		//determine the size f the data set and therefore the size of the array
		array	=	new byte[dataSize][];
		Multimap<String, byte[]> gamma	=	ArrayListMultimap.create();
		long startTime =System.nanoTime();

		for (String word : listOfKeyword){

			counter++;
			if (((float) counter/10000)		==	(int) (counter/10000)){
				System.out.println("Counter "+counter);
			}

			//generate the tag
			byte[] key1	=	CryptoPrimitives.generateCmac(key, 1	+word);
			byte[] key2	=	CryptoPrimitives.generateCmac(key, 2	+word);
			int t	=	(int) Math.ceil((float) lookup.get(word).size()/bigBlock);

			if (lookup.get(word).size()<=smallBlock){
				//pad DB(w) to "small block"
				byte[] l	=	CryptoPrimitives.generateCmac(key1, Integer.toString(0));

				gamma.put(new String(l), CryptoPrimitives.encryptAES_CTR_String(key2, CryptoPrimitives.randomBytes(16),"1 "+lookup.get(word).toString(), smallBlock	*	sizeOfFileIdentifer));
			}

			else {


				List<String> listArrayIndex	=	new ArrayList<String>();

				for (int j=0; j<t;j++){

					List<String> tmpList	=	new ArrayList<String>(lookup.get(word));

					if (j != t-1){
						tmpList	=	tmpList.subList(j *bigBlock, (j+1) *bigBlock);
					}
					else{
						int sizeList	=	tmpList.size();

						tmpList =	tmpList.subList(j*bigBlock, tmpList.size());


						for (int s=0; s<((j+1)*bigBlock	-sizeList);	s++){
							tmpList.add("XX");
						}

					}


					// generate the integer which is associated to free[b]


					byte[] randomBytes	=	CryptoPrimitives.randomBytes((int) Math.ceil(((float) Math.log(free.size())/(Math.log(2)*8))));

					int position	=	CryptoPrimitives.getIntFromByte(randomBytes, (int) Math.ceil(Math.log(free.size())/Math.log(2)));

					while (position>=free.size()-1){
						position=position/2;
					}

					int tmpPos	=	free.get(position);
					array[tmpPos]		=	CryptoPrimitives.encryptAES_CTR_String(key2, CryptoPrimitives.randomBytes(16),tmpList.toString(), bigBlock	*	sizeOfFileIdentifer);
					listArrayIndex.add(tmpPos+"");


					free.remove(position);

				}



				//medium case
				if (t <= smallBlock){
					byte[] l	=	CryptoPrimitives.generateCmac(key1, Integer.toString(0));
					gamma.put(new String(l), CryptoPrimitives.encryptAES_CTR_String(key2, CryptoPrimitives.randomBytes(16),	"2 "+listArrayIndex.toString(), smallBlock	*	sizeOfFileIdentifer));
				}
				//big case
				else{
					int tPrime	=	(int) Math.ceil((float) t/bigBlock);


					List<String> listArrayIndexTwo	=	new ArrayList<String>();

					for (int l=0; l<tPrime;l++){
						List<String> tmpListTwo	=	new ArrayList<String>(listArrayIndex);

						if (l != tPrime-1){
							tmpListTwo	=	tmpListTwo.subList(l *bigBlock, (l+1) *bigBlock);
						}
						else{

							int sizeList	=	tmpListTwo.size();

							tmpListTwo =	tmpListTwo.subList(l*bigBlock, tmpListTwo.size());
							for (int s=0; s<((l+1)*bigBlock	-sizeList);	s++){
								tmpListTwo.add("XX");
							}						
						}



						// generate the integer which is associated to free[b]


						byte[] randomBytes	=	CryptoPrimitives.randomBytes((int) Math.ceil((Math.log(free.size())/(Math.log(2)*8))));

						int position	=	CryptoPrimitives.getIntFromByte(randomBytes, (int) Math.ceil(Math.log(free.size())/Math.log(2)));

						while (position>=free.size()){
							position=position/2;
						}


						int tmpPos	=	free.get(position);



						array[tmpPos]	=	CryptoPrimitives.encryptAES_CTR_String(key2, CryptoPrimitives.randomBytes(16),tmpListTwo.toString(), bigBlock	*	sizeOfFileIdentifer);

						listArrayIndexTwo.add(tmpPos+"");

						free.remove(position);


					}


					//Pad the second set of identifiers


					byte[] l	=	CryptoPrimitives.generateCmac(key1, Integer.toString(0));
					gamma.put(new String(l), CryptoPrimitives.encryptAES_CTR_String(key2, CryptoPrimitives.randomBytes(16),	"3 "+listArrayIndexTwo.toString(), smallBlock	*	sizeOfFileIdentifer));

				}


			}



		}
		long endTime   = System.nanoTime();
		long totalTime = endTime - startTime;
		System.out.println("Time for one (w, id) "+totalTime/lookup.size());
		return gamma;
	}






	//***********************************************************************************************//

	/////////////////////    Token generation	based On NDSS paper /////////////////////////////

	//***********************************************************************************************//		


	//output two keys

	public static byte[][] genToken(byte[] key, String word) throws UnsupportedEncodingException{

		byte[][] keys	=	new byte[2][];
		keys[0]	=	CryptoPrimitives.generateCmac(key, 1	+word);
		keys[1]	=	CryptoPrimitives.generateCmac(key, 2	+word);

		return keys;
	}



	//***********************************************************************************************//

	/////////////////////    TestSI	 /////////////////////////////

	//***********************************************************************************************//	



	public static List<String> testSI(byte[][] keys, Multimap<String, byte[]> dictionary, byte[][] array) throws InvalidKeyException, InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, IOException{

		byte[] l	=	CryptoPrimitives.generateCmac(keys[0], Integer.toString(0));

		List<byte[]> tempList	=	new ArrayList<byte[]>(dictionary.get(new String(l)));

		if (!(tempList.size()==0)){
			String temp	=	(new String(CryptoPrimitives.decryptAES_CTR_String(tempList.get(0), keys[1]))).split("\t\t\t")[0];
			temp	=	temp.replaceAll("\\s", "");
			temp	=	temp.replace('[', ',');
			temp	=	temp.replace("]","");

			String[] result	=	temp.split(",");


			List<String> resultFinal	=	new ArrayList<String>(Arrays.asList(result));
			// We remove the flag that identifies the size of the dataset



			if (result[0].equals("1")){

				resultFinal.remove(0);	
				return resultFinal;
			}

			else if (result[0].equals("2")){
				resultFinal.remove(0);	

				List<String> resultFinal2	=	new ArrayList<String>();

				for (String key : resultFinal){

					boolean flag	=	true;
					int counter	=0;
					while (flag){

						if (counter <key.length() && Character.isDigit(key.charAt(counter))){

							counter++;
						}

						else{
							flag	=	false;
						}
					}

					String temp2	=	(new String(CryptoPrimitives.decryptAES_CTR_String(array[Integer.parseInt((String) key.subSequence(0, counter))], keys[1]))).split("\t\t\t")[0];
					temp2	=	temp2.replaceAll("\\s", "");

					temp2	=	temp2.replaceAll(",XX", "");

					temp2	=	temp2.replace("[", "");
					temp2	=	temp2.replace("]","");



					String[] result3	=	temp2.split(",");

					List<String> tmp	=	new ArrayList<String>(Arrays.asList(result3));
					resultFinal2.addAll(tmp);
				}

				return resultFinal2;
			}

			else if (result[0].equals("3")){
				resultFinal.remove(0);	
				List<String> resultFinal2	=	new ArrayList<String>();
				for (String key : resultFinal){

					boolean flag	=	true;
					int counter	=0;
					while (flag){

						if (counter <key.length() && Character.isDigit(key.charAt(counter))){

							counter++;
						}

						else{
							flag	=	false;
						}
					}
					String temp2	=	(new String(CryptoPrimitives.decryptAES_CTR_String(array[Integer.parseInt((String) key.subSequence(0, counter))], keys[1]))).split("\t\t\t")[0];
					temp2	=	temp2.replaceAll("\\s", "");

					temp2	=	temp2.replaceAll(",XX", "");
					temp2	=	temp2.replace("[", "");
					temp2	=	temp2.replace("]","");

					String[] result3	=	temp2.split(",");
					List<String> tmp	=	new ArrayList<String>(Arrays.asList(result3));
					resultFinal2.addAll(tmp);
				}
				List<String> resultFinal3	=	new ArrayList<String>();

				for (String key :resultFinal2){

					boolean flag	=	true;
					int counter	=0;
					while (flag){

						if (counter <key.length() && Character.isDigit(key.charAt(counter))){

							counter++;
						}

						else{
							flag	=	false;
						}
					}
					String temp2	=	(new String(CryptoPrimitives.decryptAES_CTR_String(array[Integer.parseInt((String) key.subSequence(0, counter))], keys[1]))).split("\t\t\t")[0];
					temp2	=	temp2.replaceAll("\\s", "");
					temp2	=	temp2.replaceAll(",XX", "");

					temp2	=	temp2.replace("[", "");
					temp2	=	temp2.replace("]","");
					String[] result3	=	temp2.split(",");

					List<String> tmp	=	new ArrayList<String>(Arrays.asList(result3));

					resultFinal3.addAll(tmp);
				}

				return resultFinal3;
			}
		}
		return new ArrayList<String>();
	}
}